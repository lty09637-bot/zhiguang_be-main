package com.tongji.auth.token;

import lombok.RequiredArgsConstructor;
import com.tongji.auth.config.AuthProperties;
import com.tongji.user.domain.User;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * JWT 令牌服务。
 * <p>
 * 功能：签发 Access/Refresh Token（RS256），解码 JWT，提取用户 ID、令牌类型与令牌 ID。
 * 声明：
 * - `token_type`：标识 access 或 refresh；
 * - `uid`：用户 ID；
 * - `jti`：令牌 ID（用作 Refresh Token 的白名单键）。
 * 过期时间：来自 `AuthProperties.jwt.accessTokenTtl` 与 `refreshTokenTtl`。
 */
@Service
@RequiredArgsConstructor
public class JwtService {

    private static final String CLAIM_TOKEN_TYPE = "token_type";
    private static final String CLAIM_USER_ID = "uid";

    private final JwtEncoder jwtEncoder;
    private final JwtDecoder jwtDecoder;
    private final AuthProperties properties;
    private final Clock clock = Clock.systemUTC();

    /**
     * 为指定用户签发一对 Access/Refresh Token。
     * <p>
     * 令牌类型通过 `token_type` 声明区分；Refresh Token 的 `jti` 用于白名单存储与撤销。
     * 过期时间取自配置 `AuthProperties.jwt`。
     *
     * @param user 用户实体。
     * @return 令牌对与对应过期时间及刷新令牌 ID。
     */
    public TokenPair issueTokenPair(User user) {
        String refreshTokenId = UUID.randomUUID().toString();
        Instant issuedAt = Instant.now(clock);
        Instant accessExpiresAt = issuedAt.plus(properties.getJwt().getAccessTokenTtl());
        Instant refreshExpiresAt = issuedAt.plus(properties.getJwt().getRefreshTokenTtl());

        String accessToken = encodeToken(user, issuedAt, accessExpiresAt, "access", UUID.randomUUID().toString());
        String refreshToken = encodeRefreshToken(user, issuedAt, refreshExpiresAt, refreshTokenId);
        return new TokenPair(accessToken, accessExpiresAt, refreshToken, refreshExpiresAt, refreshTokenId);
    }

    /**
     * 解码 JWT 字符串为 {@link Jwt}。
     *
     * @param token JWT 字符串。
     * @return 解析后的 JWT 对象。
     * 自动验证（是否被篡改、是否过期、签名是否正确），
     * 验证通过后返回Jwt对象（可读取里面的用户 ID、令牌类型等）；
     * 如果验证失败（比如令牌被改、过期），这个方法会直接抛异常，不用自己写校验逻辑
     */
    public Jwt decode(String token) {
        return jwtDecoder.decode(token);
    }

    /**
     * 编码访问令牌。
     *
     * @param user      用户实体，作为 subject 与自定义声明来源。
     * @param issuedAt  签发时间。
     * @param expiresAt 过期时间。
     * @param tokenType 令牌类型（"access"）。
     * @param tokenId   令牌 ID（jti）。
     * @return 编码后的 JWT 字符串。
     */
    private String encodeToken(User user, Instant issuedAt, Instant expiresAt, String tokenType, String tokenId) {
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.getJwt().getIssuer())
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .subject(String.valueOf(user.getId()))
                .id(tokenId)
                .claim(CLAIM_TOKEN_TYPE, tokenType)
                .claim(CLAIM_USER_ID, user.getId())
                .claim("nickname", user.getNickname())
                .build();
        return jwtEncoder.encode(JwtEncoderParameters.from(claims)).getTokenValue();
    }

    /**
     * 编码刷新令牌。
     *
     * @param user      用户实体。
     * @param issuedAt  签发时间。
     * @param expiresAt 过期时间。
     * @param tokenId   刷新令牌 ID（jti）。
     * @return 编码后的刷新令牌字符串。
     */
    private String encodeRefreshToken(User user, Instant issuedAt, Instant expiresAt, String tokenId) {
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.getJwt().getIssuer())
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .subject(String.valueOf(user.getId()))
                .id(tokenId)
                .claim(CLAIM_TOKEN_TYPE, "refresh")
                .claim(CLAIM_USER_ID, user.getId())
                .build();
        return jwtEncoder.encode(JwtEncoderParameters.from(claims)).getTokenValue();
    }

    /**
     * 从 JWT 中提取用户 ID。
     *
     * @param jwt 已解析的 JWT。
     * @return 用户 ID（long）。
     * @throws IllegalArgumentException 当声明类型不合法时抛出。
     */
    public long extractUserId(Jwt jwt) {
        Object claim = jwt.getClaims().get(CLAIM_USER_ID);
        if (claim instanceof Number number) {
            return number.longValue();
        }
        if (claim instanceof String text) {
            return Long.parseLong(text);
        }
        throw new IllegalArgumentException("Invalid user id in token");
    }

    /**
     * 提取令牌类型声明。
     *
     * @param jwt 已解析的 JWT。
     * @return 令牌类型字符串（例如："access" 或 "refresh"）。
     * 核心作用：后端收到令牌后，先判断类型
     * —— 只有 refreshToken 才能用来换 accessToken，accessToken 不行
     */
    public String extractTokenType(Jwt jwt) {
        Object claim = jwt.getClaims().get(CLAIM_TOKEN_TYPE);
        return claim != null ? claim.toString() : "";
    }

    /**
     * 提取令牌 ID（jti）。
     *
     * @param jwt 已解析的 JWT。
     * @return 令牌 ID。
     * 对 refreshToken：提取这个 ID，去 Redis 查是否在白名单里（在就允许刷新，不在就拒绝）；
     * 踢用户下线：直接删除 Redis 里这个 ID 对应的记录，refreshToken 就失效了。
     */
    public String extractTokenId(Jwt jwt) {
        return jwt.getId();
    }
}
