package com.policydesk.api.quota;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Redis 实现：部署形态用这个。
 *
 * <p>★ 两个刻意的细节：
 * <ol>
 *   <li><b>先 INCR 再判 1 设过期</b>：只有"第一次创建这个键"时才设置 TTL。
 *       如果每次都 SET EXPIRE，一个持续请求的用户会把过期时间一直往后推，
 *       于是"每天 0 点清零"永远不生效 —— 这是配额实现里最常见的 bug。</li>
 *   <li><b>INCR 是原子的</b>：多实例并发时不会丢计数（内存实现做不到这点）。</li>
 * </ol>
 */
@Component
@ConditionalOnProperty(name = "policy-desk.quota.backend", havingValue = "redis")
public class RedisQuotaStore implements QuotaStore {

    private final StringRedisTemplate redis;

    public RedisQuotaStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public long increment(String key, Duration ttl) {
        Long n = redis.opsForValue().increment(key);
        long value = n == null ? 0L : n;
        if (value == 1L) {
            // 只在创建时设 TTL，见类注释第 ① 条
            redis.expire(key, ttl);
        }
        return value;
    }
}
