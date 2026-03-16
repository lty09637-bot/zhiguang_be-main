package com.tongji.auth.verification;

import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/**
 * 基于 Redis 的验证码存储实现。
 * <p>
 * 使用 Hash 结构保存 `code`、`maxAttempts` 与 `attempts`，TTL 控制有效期。
 * 校验时支持尝试计数与错误状态返回，成功后删除键以防重用。
 */
@Component
public class RedisVerificationCodeStore implements VerificationCodeStore {

    private static final String FIELD_CODE = "code";
    private static final String FIELD_MAX_ATTEMPTS = "maxAttempts";
    private static final String FIELD_ATTEMPTS = "attempts";

    private final StringRedisTemplate redisTemplate;

    public RedisVerificationCodeStore(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 保存验证码到 Redis Hash，并设置 TTL。
     *
     * @param scene       场景名称。
     * @param identifier  标识（手机号或邮箱）。
     * @param code        验证码字符串。
     * @param ttl         有效期。
     * @param maxAttempts 最大尝试次数。
     * @throws RedisSystemException 保存失败时抛出。
     */
    @Override
    public void saveCode(String scene, String identifier, String code, Duration ttl, int maxAttempts) {
        String key = buildKey(scene, identifier);
        HashOperations<String, String, String> ops = redisTemplate.opsForHash();
        try {
            ops.put(key, FIELD_CODE, code);
            ops.put(key, FIELD_MAX_ATTEMPTS, String.valueOf(maxAttempts));
            ops.put(key, FIELD_ATTEMPTS, "0");
            redisTemplate.expire(key, ttl);
        } catch (DataAccessException ex) {
            throw new RedisSystemException("Failed to save verification code", ex);
        }
    }

    /**
     * 校验验证码是否匹配，更新尝试计数并在成功时删除记录。
     *
     * @param scene      场景名称。
     * @param identifier 标识（手机号或邮箱）。
     * @param code       用户输入的验证码。
     * @return 校验结果（成功、未找到、错误、尝试过多）。
     * 后期可以尝试加锁，最多等待3秒，上锁以后10秒自动解锁（防止死锁）
     */
    @Override
    public VerificationCheckResult verify(String scene, String identifier, String code) {
        // 1. 根据业务场景和用户标识拼接出唯一的 Redis Key
        String key = buildKey(scene, identifier);
        HashOperations<String, String, String> ops = redisTemplate.opsForHash();

        // 2. 从 Redis 中一次性拉取该 Key 下的全部 Hash 字段数据
        Map<String, String> data = ops.entries(key);

        // 3. 拦截：如果数据为空，说明验证码从未发送过，或者已经过期被清理
        if (data.isEmpty()) {
            return new VerificationCheckResult(VerificationCodeStatus.NOT_FOUND, 0, 0);
        }
        // 4. 解析缓存中的核心数据
        String storedCode = data.get(FIELD_CODE); // 正确的验证码
        int maxAttempts = parseInt(data.get(FIELD_MAX_ATTEMPTS), 5); // 最大允许错误次数（默认5次）
        int attempts = parseInt(data.get(FIELD_ATTEMPTS), 0); // 历史已错误尝试的次数
        // 5. 拦截：如果之前的错误次数已经达到或超过上限，直接拒绝，防止绕过锁定机制
        if (attempts >= maxAttempts) {
            return new VerificationCheckResult(VerificationCodeStatus.TOO_MANY_ATTEMPTS, attempts, maxAttempts);
        }
        // 6. 成功分支：用户输入的验证码与缓存中的完全一致
        if (Objects.equals(storedCode, code)) {
            // 校验成功后立即删除 Redis 记录，实现“阅后即焚”，防止同一验证码被重复利用（重放攻击）
            redisTemplate.delete(key);
            return new VerificationCheckResult(VerificationCodeStatus.SUCCESS, attempts, maxAttempts);
        }
        // 7. 失败分支：验证码不匹配，需要增加错误尝试次数
        int updatedAttempts = attempts + 1;
        ops.put(key, FIELD_ATTEMPTS, String.valueOf(updatedAttempts)); // 将新的错误次数更新回 Redis
        // 8. 锁定机制：如果加上这次错误后，刚好达到了最大允许次数
        if (updatedAttempts >= maxAttempts) {
            // 触发惩罚机制：将该记录的过期时间重置为 30 分钟
            // 意味着在接下来的 30 分钟内，该用户无法再次尝试当前验证码，也侧面起到了防爆破的作用
            redisTemplate.expire(key, Duration.ofMinutes(30));
            return new VerificationCheckResult(VerificationCodeStatus.TOO_MANY_ATTEMPTS, updatedAttempts, maxAttempts);
        }
        // 9. 常规错误：验证码不匹配，但还有重试机会
        return new VerificationCheckResult(VerificationCodeStatus.MISMATCH, updatedAttempts, maxAttempts);
    }

    /**
     * 使验证码失效（删除存储记录）。
     *
     * @param scene      场景名称。
     * @param identifier 标识（手机号或邮箱）。
     */
    @Override
    public void invalidate(String scene, String identifier) {
        redisTemplate.delete(buildKey(scene, identifier));
    }

    /**
     * 生成验证码的 Redis 键名。
     *
     * @param scene      场景名称。
     * @param identifier 标识（手机号或邮箱）。
     * @return 键名字符串。
     */
    private static String buildKey(String scene, String identifier) {
        return "auth:code:%s:%s".formatted(scene, identifier);
    }

    /**
     * 解析整数字符串，失败返回默认值。
     *
     * @param value        待解析字符串。
     * @param defaultValue 解析失败时的默认值。
     * @return 整数值。
     */
    private static int parseInt(String value, int defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }
}

