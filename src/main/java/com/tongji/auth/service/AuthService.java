package com.tongji.auth.service;

import lombok.RequiredArgsConstructor;
import com.tongji.auth.api.dto.AuthResponse;
import com.tongji.auth.api.dto.AuthUserResponse;
import com.tongji.auth.api.dto.LoginRequest;
import com.tongji.auth.api.dto.PasswordResetRequest;
import com.tongji.auth.api.dto.RegisterRequest;
import com.tongji.auth.api.dto.SendCodeRequest;
import com.tongji.auth.api.dto.SendCodeResponse;
import com.tongji.auth.api.dto.TokenRefreshRequest;
import com.tongji.auth.api.dto.TokenResponse;
import com.tongji.auth.audit.LoginLogService;
import com.tongji.auth.config.AuthProperties;
import com.tongji.common.exception.BusinessException;
import com.tongji.common.exception.ErrorCode;
import com.tongji.auth.model.ClientInfo;
import com.tongji.auth.model.IdentifierType;
import com.tongji.auth.token.JwtService;
import com.tongji.auth.token.RefreshTokenStore;
import com.tongji.auth.token.TokenPair;
import com.tongji.user.domain.User;
import com.tongji.user.service.UserService;
import com.tongji.auth.util.IdentifierValidator;
import com.tongji.auth.verification.SendCodeResult;
import com.tongji.auth.verification.VerificationCheckResult;
import com.tongji.auth.verification.VerificationCodeStatus;
import com.tongji.auth.verification.VerificationScene;
import com.tongji.auth.verification.VerificationService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.oauth2.jwt.JwtException;

/**
 * 认证业务服务。
 * <p>
 * 职责：发送验证码、注册、登录、刷新令牌、登出、重置密码、查询当前用户信息。
 * 安全策略：
 * - 账号格式校验（手机号/邮箱）；
 * - 验证码状态检查（过期/错误/尝试超限）；
 * - 密码复杂度校验（长度与字符类型）；
 * - Refresh Token 白名单存储与轮换，登出/重置密码后失效旧令牌；
 * 审计：记录注册/登录成功与失败，包含渠道、IP、UA。
 * 令牌：签发 RS256 的 Access/Refresh JWT，携带 uid、token_type、jti。
 * 依赖：UserService、VerificationService、PasswordEncoder、JwtService、RefreshTokenStore、LoginLogService、AuthProperties。
 */
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserService userService;
    private final VerificationService verificationService;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final RefreshTokenStore refreshTokenStore;
    private final LoginLogService loginLogService;
    private final AuthProperties authProperties;

    /**
     * 发送验证码并返回过期信息。
     * <p>
     * 注册场景要求标识不存在；登录/重置密码场景要求标识存在。
     *
     * @param request 请求体，包含：标识类型与值、场景。
     * @return 响应体，包含目标标识、场景与验证码过期秒数。
     * @throws BusinessException 当标识格式错误或存在性不符合场景要求时抛出。
     */
    public SendCodeResponse sendCode(SendCodeRequest request) {
        // 1. 基础格式校验：检查传入的手机号或邮箱格式是否合法（如正则匹配、非空判断）
        validateIdentifier(request.identifierType(), request.identifier());
        // 2. 数据清洗与标准化：例如去除手机号前后空格、邮箱统一转小写等，确保后续查库和缓存Key的一致性
        String normalized = normalizeIdentifier(request.identifierType(), request.identifier());
        // 3. 查库比对：查询数据库判断该手机号或邮箱是否已经注册过
        boolean exists = identifierExists(request.identifierType(), normalized);
        // 4. 注册场景拦截：如果是注册验证码，但数据库里已经有这个账号了，直接抛出“账号已存在”异常
        if (request.scene() == VerificationScene.REGISTER && exists) {
            throw new BusinessException(ErrorCode.IDENTIFIER_EXISTS);
        }
        // 5. 登录/重置密码场景拦截：如果是老用户操作，但数据库里根本查不到这个账号，抛出“账号不存在”异常
        if ((request.scene() == VerificationScene.LOGIN || request.scene() == VerificationScene.RESET_PASSWORD) && !exists) {
            throw new BusinessException(ErrorCode.IDENTIFIER_NOT_FOUND);
        }
        // 6. 核心下发：所有前置业务规则校验通过后，调用底层核心服务执行“生成-限流-缓存-发送”流程
        SendCodeResult result = verificationService.sendCode(request.scene(), normalized);
        // 7. 封装响应：将底层结果对象转换为对外的 Response DTO，对外屏蔽底层可能多出的敏感字段
        return new SendCodeResponse(result.identifier(), result.scene(), result.expireSeconds());
    }

    /**
     * 注册用户并签发令牌。
     * <p>
     * 验证标识与验证码，创建用户（可选设置密码），记录审计，签发令牌对并保存刷新令牌白名单。
     *
     * @param request    注册请求，包含：标识类型与值、验证码、可选密码、是否同意协议。
     * @param clientInfo 客户端信息（IP/UA），用于登录审计。
     * @return 认证响应，包含用户信息与令牌对。
     * @throws BusinessException 当未同意协议、标识冲突、验证码失败、密码不合规时抛出。
     */
    public AuthResponse register(RegisterRequest request, ClientInfo clientInfo) {
        // 1. 合规性前置校验：强制要求用户必须明确勾选同意服务条款和隐私协议
        if (!request.agreeTerms()) {
            throw new BusinessException(ErrorCode.TERMS_NOT_ACCEPTED);
        }
        // 2. 格式校验：检查传入的手机号或邮箱格式是否合法
        validateIdentifier(request.identifierType(), request.identifier());
        // 3. 数据标准化：清洗标识符（如去空格、邮箱转小写），确保全局唯一性比对准确
        String identifier = normalizeIdentifier(request.identifierType(), request.identifier());
        // 4. 防重校验：确认该账号没有被注册过，防止并发请求导致的数据冲突
        if (identifierExists(request.identifierType(), identifier)) {
            throw new BusinessException(ErrorCode.IDENTIFIER_EXISTS);
        }
        // 5. 核心安全防线：校验验证码是否正确（底层调用的就是我们最初讨论的 verify 方法）
        ensureVerificationSuccess(verificationService.verify(VerificationScene.REGISTER, identifier, request.code()));
        // 6. 构建新用户实体：根据注册类型（手机/邮箱）填充字段，并初始化默认昵称、头像等基础信息
        User user = User.builder()
                .phone(request.identifierType() == IdentifierType.PHONE ? identifier : null)
                .email(request.identifierType() == IdentifierType.EMAIL ? identifier : null)
                .nickname(generateNickname())
                .avatar("https://static.zhiguang.cn/default-avatar.png")
                .bio(null)
                .tagsJson("[]")
                .build();
        // 7. 密码处理（可选）：如果用户在注册时顺便设置了密码，进行复杂度校验并执行哈希加密(如BCrypt)后存储
        if (StringUtils.hasText(request.password())) {
            validatePassword(request.password());
            user.setPasswordHash(passwordEncoder.encode(request.password().trim()));
        }
        // 8. 数据落盘：将组装好的新用户信息插入数据库（获取到自增的主键ID）
        userService.createUser(user);
        // 9. 状态保持（注册即登录）：为新创建的用户直接签发 JWT 双 Token（AccessToken + RefreshToken）
        TokenPair tokenPair = jwtService.issueTokenPair(user);
        // 10. 会话管理：将 RefreshToken 存入 Redis 或持久化，用于后续的 Token 无感续期和单点登录/踢人控制
        storeRefreshToken(user.getId(), tokenPair);
        // 11. 审计风控：记录本次注册行为（在业务意义上等同于首次登录），记录 IP 和设备指纹供安全分析
        loginLogService.record(user.getId(), identifier, "REGISTER", clientInfo.ip(), clientInfo.userAgent(), "SUCCESS");
        // 12. 组装响应：对内部User对象和Token对象进行脱敏/映射包装后，一并返回给前端
        return new AuthResponse(mapUser(user), mapToken(tokenPair));
    }

    /**
     * 登录并签发令牌。
     * <p>
     * 支持密码或验证码通道；成功后记录审计，签发令牌对并保存刷新令牌白名单。
     *
     * @param request    登录请求，包含：标识类型与值、密码或验证码（二选一）。
     * @param clientInfo 客户端信息（IP/UA），用于登录审计。
     * @return 认证响应，包含用户信息与令牌对。
     * @throws BusinessException 当用户不存在、凭证错误或请求不合法时抛出。
     */
    public AuthResponse login(LoginRequest request, ClientInfo clientInfo) {
        // 1. 基础校验：检查前端传来的账号格式是否合法（如手机号位数、邮箱格式等）
        validateIdentifier(request.identifierType(), request.identifier());

        // 2. 数据清洗：统一格式化账号（如去除首尾空格、邮箱强转小写等），防止因为大小写导致查库失败
        String identifier = normalizeIdentifier(request.identifierType(), request.identifier());

        // 3. 查询底档：去数据库中根据账号类型和清洗后的账号查找用户
        Optional<User> userOptional = findUserByIdentifier(request.identifierType(), identifier);

        // 如果查无此人，直接抛出业务异常，中断后续流程
        if (userOptional.isEmpty()) {
            throw new BusinessException(ErrorCode.IDENTIFIER_NOT_FOUND);
        }

        // 将查询到的实体对象提取出来备用
        User user = userOptional.get();

        // 用于记录本次用户是通过哪种通道尝试登录的，方便记入风控日志
        String channel;

        // 4. 核心鉴权分流（双通道机制）
        // 👉 通道 A：如果前端传了密码，则走【密码登录】逻辑
        if (StringUtils.hasText(request.password())) {
            channel = "PASSWORD";

            // 安全校验：如果数据库里该用户没有设密码，或者前端传的明文密码与数据库里加盐(BCrypt)后的哈希值不匹配
            if (!StringUtils.hasText(user.getPasswordHash()) || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
                // 细节防范：在抛异常赶走用户前，先记录一笔“登录失败”的风控日志，记录下作恶 IP 和设备
                loginLogService.record(user.getId(), identifier, channel, clientInfo.ip(), clientInfo.userAgent(), "FAILED");
                throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
            }
        }
        // 👉 通道 B：如果前端没传密码，但传了验证码，则走【验证码免密登录】逻辑
        else if (StringUtils.hasText(request.code())) {
            channel = "CODE";
            // 呼叫底层验证码服务，去 Redis 里核对该账号在 "LOGIN" 场景下的验证码是否正确、是否过期或错超次数
            ensureVerificationSuccess(verificationService.verify(VerificationScene.LOGIN, identifier, request.code()));
        }
        // 👉 异常分支：既没有密码也没有验证码，直接打回要求重填
        else {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "请提供验证码或密码");
        }
        // 5. 签发凭证：安检全部通过，利用当前用户信息签发一对全新的 Access Token 和 Refresh Token
        TokenPair tokenPair = jwtService.issueTokenPair(user);
        // 6. 状态入库：将新生成的 Refresh Token ID 存入 Redis 白名单，并设置精准的 TTL，用于后续无感刷新拦截
        storeRefreshToken(user.getId(), tokenPair);
        // 7. 审计记录：记录一笔成功的风控日志
        loginLogService.record(user.getId(), identifier, channel, clientInfo.ip(), clientInfo.userAgent(), "SUCCESS");
        // 8. 满载而归：将脱敏后的用户基本信息和最新生成的 Token 对打包返回给前端
        return new AuthResponse(mapUser(user), mapToken(tokenPair));
    }

    /**
     * 使用刷新令牌获取新的令牌对。
     * <p>
     * 校验刷新令牌类型与白名单有效性，签发新令牌后撤销旧刷新令牌并存储新令牌。
     *
     * @param request 刷新请求，包含：refreshToken。
     * @return 新的令牌响应。
     * @throws BusinessException 当刷新令牌无效或用户不存在时抛出。
     */
    public TokenResponse refresh(TokenRefreshRequest request) {
        Jwt jwt = decodeRefreshToken(request.refreshToken());

        if (!Objects.equals("refresh", jwtService.extractTokenType(jwt))) {
            throw new BusinessException(ErrorCode.REFRESH_TOKEN_INVALID);
        }

        long userId = jwtService.extractUserId(jwt);
        String tokenId = jwtService.extractTokenId(jwt);

        if (!refreshTokenStore.isTokenValid(userId, tokenId)) {
            throw new BusinessException(ErrorCode.REFRESH_TOKEN_INVALID);
        }

        User user = findUserById(userId).orElseThrow(() -> new BusinessException(ErrorCode.IDENTIFIER_NOT_FOUND));
        TokenPair tokenPair = jwtService.issueTokenPair(user);
        refreshTokenStore.revokeToken(userId, tokenId);
        storeRefreshToken(userId, tokenPair);

        return mapToken(tokenPair);
    }

    /**
     * 登出：撤销指定刷新令牌。
     *
     * @param refreshToken 刷新令牌字符串；若解析为合法刷新令牌则撤销其白名单记录。
     */
    public void logout(String refreshToken) {
        decodeRefreshTokenSafely(refreshToken).ifPresent(jwt -> {
            if (Objects.equals("refresh", jwtService.extractTokenType(jwt))) {
                long userId = jwtService.extractUserId(jwt);
                String tokenId = jwtService.extractTokenId(jwt);
                refreshTokenStore.revokeToken(userId, tokenId);
            }
        });
    }

    /**
     * 使用验证码重置密码并使刷新令牌失效。
     *
     * @param request 重置请求，包含：标识类型与值、验证码、新密码。
     * @throws BusinessException 当标识不存在、验证码失败或密码策略不满足时抛出。
     */
    public void resetPassword(PasswordResetRequest request) {
        validateIdentifier(request.identifierType(), request.identifier());
        validatePassword(request.newPassword());
        String identifier = normalizeIdentifier(request.identifierType(), request.identifier());
        User user = findUserByIdentifier(request.identifierType(), identifier)
                .orElseThrow(() -> new BusinessException(ErrorCode.IDENTIFIER_NOT_FOUND));
        ensureVerificationSuccess(verificationService.verify(VerificationScene.RESET_PASSWORD, identifier, request.code()));
        user.setPasswordHash(passwordEncoder.encode(request.newPassword().trim()));
        userService.updatePassword(user);
        refreshTokenStore.revokeAll(user.getId());
    }

    /**
     * 查询用户概要信息。
     *
     * @param userId 用户 ID。
     * @return 用户概要响应。
     * @throws BusinessException 当用户不存在时抛出。
     */
    public AuthUserResponse me(long userId) {
        User user = findUserById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.IDENTIFIER_NOT_FOUND));
        return mapUser(user);
    }

    /**
     * 保证验证码校验成功，否则按状态抛出对应业务异常。
     *
     * @param result 验证码校验结果。
     */
    private void ensureVerificationSuccess(VerificationCheckResult result) {
        if (result.isSuccess()) {
            return;
        }
        VerificationCodeStatus status = result.status();
        if (status == VerificationCodeStatus.NOT_FOUND || status == VerificationCodeStatus.EXPIRED) {
            throw new BusinessException(ErrorCode.VERIFICATION_NOT_FOUND);
        }
        if (status == VerificationCodeStatus.MISMATCH) {
            throw new BusinessException(ErrorCode.VERIFICATION_MISMATCH);
        }
        if (status == VerificationCodeStatus.TOO_MANY_ATTEMPTS) {
            throw new BusinessException(ErrorCode.VERIFICATION_TOO_MANY_ATTEMPTS);
        }
        throw new BusinessException(ErrorCode.BAD_REQUEST, "验证码校验失败");
    }

    /**
     * 校验标识（手机号/邮箱）的格式。
     *
     * @param type       标识类型：PHONE 或 EMAIL。
     * @param identifier 标识值。
     * @throws BusinessException 当格式不合法时抛出。
     */
    private void validateIdentifier(IdentifierType type, String identifier) {
        if (type == IdentifierType.PHONE && !IdentifierValidator.isValidPhone(identifier)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "手机号格式错误");
        }
        if (type == IdentifierType.EMAIL && !IdentifierValidator.isValidEmail(identifier)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "邮箱格式错误");
        }
    }

    /**
     * 校验密码策略：非空、最小长度、必须包含字母和数字。
     *
     * @param password 明文密码。
     * @throws BusinessException 当密码不满足策略时抛出。
     */
    private void validatePassword(String password) {
        if (!StringUtils.hasText(password)) {
            throw new BusinessException(ErrorCode.PASSWORD_POLICY_VIOLATION, "密码不能为空");
        }
        String trimmed = password.trim();
        if (trimmed.length() < authProperties.getPassword().getMinLength()) {
            throw new BusinessException(ErrorCode.PASSWORD_POLICY_VIOLATION, "密码长度至少" + authProperties.getPassword().getMinLength() + "位");
        }
        boolean hasLetter = trimmed.chars().anyMatch(Character::isLetter);
        boolean hasDigit = trimmed.chars().anyMatch(Character::isDigit);
        if (!hasLetter || !hasDigit) {
            throw new BusinessException(ErrorCode.PASSWORD_POLICY_VIOLATION, "密码需包含字母和数字");
        }
    }

    /**
     * 判断标识是否已存在。
     *
     * @param type       标识类型：PHONE 或 EMAIL。
     * @param identifier 标识值（需为标准化格式）。
     * @return 是否存在。
     */
    private boolean identifierExists(IdentifierType type, String identifier) {
        return switch (type) {
            case PHONE -> userService.existsByPhone(identifier);
            case EMAIL -> userService.existsByEmail(identifier);
        };
    }

    /**
     * 根据标识查找用户。
     *
     * @param type       标识类型：PHONE 或 EMAIL。
     * @param identifier 标识值（需为标准化格式）。
     * @return 用户 Optional。
     */
    private Optional<User> findUserByIdentifier(IdentifierType type, String identifier) {
        return switch (type) {
            case PHONE -> userService.findByPhone(identifier);
            case EMAIL -> userService.findByEmail(identifier);
        };
    }

    /**
     * 根据 ID 查找用户。
     *
     * @param userId 用户 ID。
     * @return 用户 Optional。
     */
    private Optional<User> findUserById(long userId) {
        return userService.findById(userId);
    }

    /**
     * 标准化标识文本：手机号去空格、邮箱转小写并去空格。
     *
     * @param type       标识类型：PHONE 或 EMAIL。
     * @param identifier 原始标识文本。
     * @return 标准化后的标识文本。
     */
    private String normalizeIdentifier(IdentifierType type, String identifier) {
        return switch (type) {
            case PHONE -> identifier.trim();
            case EMAIL -> identifier.trim().toLowerCase(Locale.ROOT);
        };
    }

    /**
     * 存储刷新令牌白名单记录。
     *
     * @param userId    用户 ID。
     * @param tokenPair 令牌对（含刷新令牌 ID 与过期时间）。
     */
    private void storeRefreshToken(Long userId, TokenPair tokenPair) {
        Duration ttl = Duration.between(Instant.now(), tokenPair.refreshTokenExpiresAt());
        if (ttl.isNegative()) {
            ttl = Duration.ZERO;
        }
        refreshTokenStore.storeToken(userId, tokenPair.refreshTokenId(), ttl);
    }

    /**
     * 映射用户实体到响应对象。
     *
     * @param user 用户实体。
     * @return 用户响应。
     */
    private AuthUserResponse mapUser(User user) {
        return new AuthUserResponse(
                user.getId(),
                user.getNickname(),
                user.getAvatar(),
                user.getPhone(),
                user.getZgId(),
                user.getBirthday(),
                user.getSchool(),
                user.getBio(),
                user.getGender(),
                user.getTagsJson()
        );
    }

    /**
     * 映射令牌对到响应对象。
     *
     * @param tokenPair 令牌对。
     * @return 令牌响应。
     */
    private TokenResponse mapToken(TokenPair tokenPair) {
        return new TokenResponse(tokenPair.accessToken(), tokenPair.accessTokenExpiresAt(), tokenPair.refreshToken(), tokenPair.refreshTokenExpiresAt());
    }

    /**
     * 生成默认昵称。
     *
     * @return 随机昵称字符串。
     */
    private String generateNickname() {
        return "知光用户" + UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * 解码刷新令牌，失败时抛业务异常。
     *
     * @param refreshToken 刷新令牌字符串。
     * @return 解析得到的 JWT。
     * @throws BusinessException 当刷新令牌无法解析时抛出。
     */
    private Jwt decodeRefreshToken(String refreshToken) {
        try {
            return jwtService.decode(refreshToken);
        } catch (JwtException ex) {
            throw new BusinessException(ErrorCode.REFRESH_TOKEN_INVALID);
        }
    }

    /**
     * 安全解码刷新令牌，失败时返回空 Optional。
     *
     * @param refreshToken 刷新令牌字符串。
     * @return 成功时返回 JWT，失败时返回 Optional.empty()。
     */
    private Optional<Jwt> decodeRefreshTokenSafely(String refreshToken) {
        try {
            return Optional.of(jwtService.decode(refreshToken));
        } catch (JwtException ex) {
            return Optional.empty();
        }
    }
}
