package com.policydesk.api.quota;

import java.time.Duration;

/**
 * 配额计数的存储抽象。
 *
 * <p>★ 为什么抽成接口而不是直接写 Redis：
 * 判据要能在**没有 Redis 的机器上**跑（否则测试会因为"本机没装 Redis"而常年 skip，
 * 而 skip 掉的判据等于没有）。所以：测试与本地演示用内存实现，部署用 Redis 实现，
 * 两者共享同一个 {@link com.policydesk.api.service.QuotaService} 的计数语义。
 *
 * <p>★ 两种实现的差异必须写清楚，不能假装一样：
 * <ul>
 *   <li>内存：进程内，**重启清零**，多实例各算各的 —— 只适合单机演示与测试；</li>
 *   <li>Redis：跨进程、带 TTL 自动过期 —— 部署形态必须用它。</li>
 * </ul>
 */
public interface QuotaStore {

    /**
     * 自增并返回自增后的值（原子的）。
     *
     * @param key 计数键
     * @param ttl 首次创建时设置的过期时间（跨天自动清零）
     */
    long increment(String key, Duration ttl);
}
