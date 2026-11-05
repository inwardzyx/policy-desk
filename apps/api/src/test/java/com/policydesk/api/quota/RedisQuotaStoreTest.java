package com.policydesk.api.quota;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Redis 实现的判据（用 mock 的 template，不需要真 Redis）。
 *
 * <p>★ 这里唯一值钱的一条：**TTL 只在首次创建（INCR 返回 1）时设置**。
 * 如果写成"每次 INCR 都 EXPIRE"，一个持续提问的用户会把过期时间一直往后推，
 * "每天 0 点清零"永远不生效 —— 而它看起来完全正常（计数确实在涨）。
 * 这条判据就是钉住"第二次不再 EXPIRE"。
 */
class RedisQuotaStoreTest {

    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> ops = mock(ValueOperations.class);
    private final StringRedisTemplate template = mock(StringRedisTemplate.class);
    private final RedisQuotaStore store = new RedisQuotaStore(template);

    private void givenIncrementReturns(Long value) {
        when(template.opsForValue()).thenReturn(ops);
        when(ops.increment(anyString())).thenReturn(value);
    }

    @Test
    @DisplayName("第一次（返回 1）⇒ 设置 TTL")
    void first_increment_sets_ttl() {
        givenIncrementReturns(1L);

        long n = store.increment("quota:7:2026-11-05", Duration.ofHours(14));

        assertThat(n).isEqualTo(1L);
        verify(template).expire(eq("quota:7:2026-11-05"), any(Duration.class));
    }

    @Test
    @DisplayName("第二次（返回 5）⇒ **不再**设置 TTL（否则永不过期）")
    void later_increment_does_not_touch_ttl() {
        givenIncrementReturns(5L);

        long n = store.increment("quota:7:2026-11-05", Duration.ofHours(14));

        assertThat(n).isEqualTo(5L);
        verify(template, never()).expire(anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("Redis 返回 null（极少数异常情形）⇒ 当作 0，不抛 NPE")
    void null_increment_is_zero() {
        givenIncrementReturns(null);

        assertThat(store.increment("k", Duration.ofHours(1))).isZero();
    }
}
