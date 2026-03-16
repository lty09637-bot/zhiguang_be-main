package com.tongji.auth.verification;

import lombok.RequiredArgsConstructor;
import com.tongji.auth.config.AuthProperties;
import com.tongji.common.exception.BusinessException;
import com.tongji.common.exception.ErrorCode;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 验证码业务服务。
 * <p>
 * 负责发送与校验验证码：
 * - 速率限制与日限额；
 * - 随机码生成与存储；
 * - 调用发送器进行实际发送；
 * 配置来源于 `AuthProperties.Verification`。
 */
@Service
@RequiredArgsConstructor
public class VerificationService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final DateTimeFormatter DAY_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final VerificationCodeStore codeStore;
    private final CodeSender codeSender;
    private final StringRedisTemplate stringRedisTemplate;
    private final AuthProperties properties;

    /**
     * 发送验证码到指定标识。
     * <p>
     * 执行发送间隔与日次数限制，生成随机数字验证码，保存到存储并调用发送器。
     *
     * @param scene      验证码场景（REGISTER/LOGIN/RESET_PASSWORD）。
     * @param identifier 标识（手机号或邮箱）。
     * @return 发送结果，包含标识、场景与过期秒数。
     * @throws BusinessException 参数不完整或触发速率/日限额时抛出。
     */
    public SendCodeResult sendCode(VerificationScene scene, String identifier) {
        // 1. 基础防线：校验必传参数，防止空指针或无效的空字符串请求
        if (scene == null || !StringUtils.hasText(identifier)) {
            // 参数不合法时直接阻断并抛出业务异常，提示前端或调用方检查参数
            throw new BusinessException(ErrorCode.BAD_REQUEST, "请提供正确的验证码发送参数");
        }
        // 2. 读取配置：获取当前系统关于验证码的全局配置（如长度、有效期、冷却时间、每日上限）
        AuthProperties.Verification cfg = properties.getVerification();
        // 3. 频率防刷：检查距离该用户上一次获取验证码是否已经过了冷却时间
        enforceSendInterval(scene, identifier, cfg.getSendInterval());
        // 4. 额度防刷：检查该用户今天在当前业务场景下的总发送次数是否已达阈值
        enforceDailyLimit(scene, identifier, cfg.getDailyLimit());
        // 5. 生成凭证：根据配置的长度（通常为4位或6位）生成纯数字随机验证码
        String code = generateNumericCode(cfg.getCodeLength());
        // 6. 缓存落地：将生成的验证码存入底层存储（如Redis），并附带过期时间(TTL)和允许的最大错误尝试次数
        codeStore.saveCode(scene.name(), identifier, code, cfg.getTtl(), cfg.getMaxAttempts());
        // 7. 触达用户：调用具体的发送通道（如阿里云短信网关、邮件服务）将验证码实际发送给用户
        codeSender.sendCode(scene, identifier, code, (int) cfg.getTtl().toMinutes());
        // 8. 返回结果：将发送标识、场景以及换算成秒的有效期返回，方便前端直接用于实现倒计时UI
        return new SendCodeResult(identifier, scene, (int) cfg.getTtl().toSeconds());
    }

    /**
     * 校验验证码是否正确且未超限。
     *
     * @param scene      验证码场景。
     * @param identifier 标识（手机号或邮箱）。
     * @param code       用户输入的验证码。
     * @return 校验结果，包含状态与尝试次数统计。
     * @throws BusinessException 参数不完整时抛出。
     */
    public VerificationCheckResult verify(VerificationScene scene, String identifier, String code) {
        if (scene == null || !StringUtils.hasText(identifier) || !StringUtils.hasText(code)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "验证码校验参数不完整");
        }
        return codeStore.verify(scene.name(), identifier, code);
    }

    /**
     * 使验证码失效（删除存储记录）。
     *
     * @param scene      验证码场景。
     * @param identifier 标识（手机号或邮箱）。
     */
    public void invalidate(VerificationScene scene, String identifier) {
        codeStore.invalidate(scene.name(), identifier);
    }

    /**
     * 发送间隔限制：同一标识在指定间隔内只能发送一次。
     *
     * @param scene      验证码场景。
     * @param identifier 标识（手机号或邮箱）。
     * @param interval   发送间隔。
     */
    private void enforceSendInterval(VerificationScene scene, String identifier, Duration interval) {
        // 1. 防御性编程：如果配置的间隔时间为0或负数，视为不限制发送频率，直接放行
        if (interval.isZero() || interval.isNegative()) {
            return;
        }
        // 2. 拼接Redis Key：记录该用户在特定场景下的最后一次发送状态
        String key = "auth:code:last:" + scene.name() + ":" + identifier;
        // 3. 尝试从Redis中获取该Key，判断当前是否还处于冷却期内
        String existing = stringRedisTemplate.opsForValue().get(key);
        // 4. 核心拦截逻辑：如果获取到了值，说明距离上次发送的时间还没超过配置的 interval
        if (existing != null) {
            // 直接抛出业务异常，外层捕获后提示用户“获取验证码过于频繁，请稍后再试”
            throw new BusinessException(ErrorCode.VERIFICATION_RATE_LIMIT);
        }
        // 5. 如果Key不存在（首次发送或已过冷却期），则写入占位值"1"，并直接将过期时间设置为 interval
        // 这样当 interval 时间一到，Redis会自动删除该Key，解除冷却锁定
        stringRedisTemplate.opsForValue().set(key, "1", interval);
    }
    /**
     * 每日发送次数限制：超过上限则抛出限额异常。
     *
     * @param scene      验证码场景。
     * @param identifier 标识（手机号或邮箱）。
     * @param limit      每日上限次数。
     */
    private void enforceDailyLimit(VerificationScene scene, String identifier, int limit) {
        // 1. 容错与开关逻辑：如果上限配置为0或负数，视作不限制，直接放行
        if (limit <= 0) {
            return;
        }
        // 2. 获取当前系统日期（如20231027），用于按天划分统计维度
        String date = DAY_FORMAT.format(LocalDate.now());
        // 3. 拼接Redis Key：固定前缀 + 业务场景 + 用户标识 + 日期后缀
        String key = "auth:code:count:" + scene.name() + ":" + identifier + ":" + date;
        // 4. 利用Redis的increment原子操作递增发送次数。若Key不存在，Redis会初始化为0再加1，返回1
        Long count = stringRedisTemplate.opsForValue().increment(key);
        // 5. 如果返回值为1，说明这是用户今天的第一次请求
        if (count != null && count == 1L) {
            // 首次创建Key时，顺手设置1天的过期时间，防止这些统计Key永久滞留占用Redis内存
            stringRedisTemplate.expire(key, Duration.ofDays(1));
        }
        // 6. 核心拦截逻辑：如果递增后的总次数大于每日配置上限
        if (count != null && count > limit) {
            // 直接抛出业务异常，外层捕获后统一返回“今日发送次数已达上限”提示
            throw new BusinessException(ErrorCode.VERIFICATION_DAILY_LIMIT);
        }
    }

    /**
     * 生成指定长度的纯数字验证码。
     *
     * @param length 验证码长度。
     * @return 数字字符串。
     */
    private static String generateNumericCode(int length) {
        StringBuilder builder = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            builder.append(RANDOM.nextInt(10));
        }
        return builder.toString();
    }
}
