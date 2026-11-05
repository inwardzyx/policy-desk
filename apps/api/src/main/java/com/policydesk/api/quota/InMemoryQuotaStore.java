package com.policydesk.api.quota;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 进程内实现：给测试与单机演示用。
 *
 * <p>★ 它**不是**"Redis 的降级备份"，而是一个独立的、明确受限的选择：
 * 重启清零、多实例各算各的。用它就必须接受这两条，别在部署时悄悄用它。
 * 选哪个由 {@code policy-desk.quota.backend} 显式决定（没有"自动降级"）。
 */
@Component
@ConditionalOnProperty(name = "policy-desk.quota.backend", havingValue = "memory", matchIfMissing = true)
public class InMemoryQuotaStore implements QuotaStore {

    private record Counter(long value, long expiresAtMillis) {
    }

    private final Map<String, Counter> counters = new ConcurrentHashMap<>();

    @Override
    public synchronized long increment(String key, Duration ttl) {
        long now = System.currentTimeMillis();
        Counter c = counters.get(key);
        if (c == null || c.expiresAtMillis() <= now) {
            c = new Counter(0, now + ttl.toMillis());
        }
        Counter next = new Counter(c.value() + 1, c.expiresAtMillis());
        counters.put(key, next);
        return next.value();
    }

    /** 测试用：清空（模拟"第二天"）。 */
    public void reset() {
        counters.clear();
    }
}
