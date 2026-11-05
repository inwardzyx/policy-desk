package com.policydesk.api.service;

import com.policydesk.api.quota.QuotaStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * 每日配额：每个用户每天 N 次问答。
 *
 * <p>★ 为什么配额放在 Java 而不是 Python（这条也是三层架构的卖点）：
 * 它必须在**调用模型之前**判定。放在 Java，超额请求根本不会打到 Python，
 * 也就不会烧 token —— 这是直接的钱。放在 Python 则要先过一遍检索/改写才可能被拒。
 *
 * <p>★ 键里带日期（{@code quota:{userId}:2026-11-05}）而不是"每天手动清零"：
 * 前者只需要 TTL 自动过期，后者需要一个定时任务，而定时任务在单机部署里最容易被人忘掉。
 */
@Service
public class QuotaService {

    public record Decision(boolean allowed, int used, int limit) {
    }

    private static final Logger log = LoggerFactory.getLogger(QuotaService.class);
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final QuotaStore store;
    private final int dailyLimit;
    private final Clock clock;

    // ★ 与 JwtService 同一个坑：类里有两个构造器（生产/测试注入时钟），
    //   不标 @Autowired 时 Spring 会去找无参构造器，然后报 "No default constructor found"。
    @org.springframework.beans.factory.annotation.Autowired
    public QuotaService(QuotaStore store,
                        @Value("${policy-desk.quota.daily-limit:12}") int dailyLimit) {
        this(store, dailyLimit, Clock.system(ZONE));
    }

    /** 测试用：注入可控时钟才能确定性地测"跨天清零"。 */
    public QuotaService(QuotaStore store, int dailyLimit, Clock clock) {
        this.store = store;
        this.dailyLimit = dailyLimit;
        this.clock = clock;
    }

    /**
     * 消耗一次配额。
     *
     * <p>★ 注意语义是"先扣再判"：被拒的那次也会占用计数。
     * 否则用户可以在超额后一直重试，每次都先过判定 —— 那就等于没有配额。
     */
    public Decision consume(long userId) {
        ZonedDateTime now = ZonedDateTime.now(clock);
        String key = "quota:" + userId + ":" + LocalDate.now(clock);
        long ttlSeconds = Math.max(60, Duration.between(now, now.toLocalDate().plusDays(1)
                .atStartOfDay(ZONE)).getSeconds());
        long used = store.increment(key, Duration.ofSeconds(ttlSeconds));
        boolean allowed = used <= dailyLimit;
        if (!allowed) {
            log.info("用户 {} 今日配额用尽（{}/{}），请求在调用模型之前被拒", userId, used, dailyLimit);
        }
        return new Decision(allowed, (int) used, dailyLimit);
    }
}
