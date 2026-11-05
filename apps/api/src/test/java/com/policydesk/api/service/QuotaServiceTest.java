package com.policydesk.api.service;

import com.policydesk.api.quota.InMemoryQuotaStore;
import com.policydesk.api.quota.QuotaStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 配额判据：用完就拒、跨天换键、被拒也计数。
 *
 * <p>★ 「被拒也计数」这条容易被写成反的：如果超额后不计数，用户可以在超额后
 * 无限重试（每次都是"第 N+1 次"判定），配额等于不存在。
 */
class QuotaServiceTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private Clock at(String iso) {
        return Clock.fixed(Instant.parse(iso), ZONE);
    }

    /** 记录键的假存储：用来验证"键里带日期"这件事，不依赖任何外部时间。 */
    private static final class RecordingStore implements QuotaStore {
        final List<String> keys = new ArrayList<>();

        @Override
        public long increment(String key, Duration ttl) {
            keys.add(key);
            return keys.size();
        }
    }

    @Test
    @DisplayName("限额内放行，超出拒绝，且**被拒那次也占用计数**")
    void allows_up_to_limit_then_denies() {
        QuotaService svc = new QuotaService(new InMemoryQuotaStore(), 3, at("2026-11-05T10:00:00Z"));

        assertThat(svc.consume(7L).allowed()).isTrue();
        assertThat(svc.consume(7L).allowed()).isTrue();
        assertThat(svc.consume(7L).allowed()).isTrue();

        QuotaService.Decision denied = svc.consume(7L);
        assertThat(denied.allowed()).isFalse();
        assertThat(denied.used()).isEqualTo(4);          // 被拒那次也算了进去
        assertThat(denied.limit()).isEqualTo(3);
    }

    @Test
    @DisplayName("不同用户互不影响（键里带 userId）")
    void users_are_isolated() {
        QuotaService svc = new QuotaService(new InMemoryQuotaStore(), 1, at("2026-11-05T10:00:00Z"));

        assertThat(svc.consume(1L).allowed()).isTrue();
        assertThat(svc.consume(1L).allowed()).isFalse();
        assertThat(svc.consume(2L).allowed()).isTrue();     // 另一个用户重新开始
    }

    @Test
    @DisplayName("键里带日期：跨天就是另一个计数器（不靠定时任务清零）")
    void key_contains_the_day() {
        RecordingStore store = new RecordingStore();
        new QuotaService(store, 10, at("2026-11-05T10:00:00Z")).consume(7L);
        new QuotaService(store, 10, at("2026-11-06T10:00:00Z")).consume(7L);

        assertThat(store.keys).hasSize(2);
        assertThat(store.keys.get(0)).isEqualTo("quota:7:2026-11-05");
        assertThat(store.keys.get(1)).isEqualTo("quota:7:2026-11-06");
    }
}
