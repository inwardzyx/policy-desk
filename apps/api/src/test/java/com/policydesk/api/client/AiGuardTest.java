package com.policydesk.api.client;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.bulkhead.Bulkhead;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 护栏的判据：**坏情况下它要响**。
 *
 * <p>★ 为什么是这四条：熔断/隔离的失败不是"报错"，而是"没反应"——
 * 请求一直挂着、线程被占满、其他接口一起变慢。所以每条都要证明"能快速失败"，
 * 而不是只证明"正常时能通过"。
 */
class AiGuardTest {

    private final AiGuard guard = new AiGuard();

    @Test
    @DisplayName("连续失败到阈值后，熔断打开并快速失败（503），不再打上游")
    void breaker_opens_and_fails_fast() {
        AtomicInteger upstreamCalls = new AtomicInteger();
        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> guard.call(() -> {
                upstreamCalls.incrementAndGet();
                throw new PythonAiClient.PythonAiException(500, "boom", null);
            })).isInstanceOf(PythonAiClient.PythonAiException.class);
        }
        assertThat(guard.breaker().getState()).isEqualTo(CircuitBreaker.State.OPEN);

        // 打开后：上游一次都不该被调用，直接 503
        upstreamCalls.set(0);
        assertThatThrownBy(() -> guard.call(() -> {
            upstreamCalls.incrementAndGet();
            return "ok";
        }))
                .isInstanceOf(PythonAiClient.PythonAiException.class)
                .satisfies(e -> assertThat(((PythonAiClient.PythonAiException) e).upstreamStatus())
                        .isEqualTo(503));
        assertThat(upstreamCalls.get()).isZero();
    }

    @Test
    @DisplayName("并发隔离：占满 2 个后，第三个立刻被拒（不排队、不占线程）")
    void bulkhead_rejects_when_full() throws Exception {
        CountDownLatch hold = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(2);
        for (int i = 0; i < 2; i++) {
            Thread t = new Thread(() -> guard.call(() -> {
                started.countDown();
                try {
                    hold.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                return "ok";
            }));
            t.setDaemon(true);
            t.start();
        }
        assertThat(started.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(guard.bulkhead().getMetrics().getAvailableConcurrentCalls()).isZero();

        assertThatThrownBy(() -> guard.call(() -> "third"))
                .isInstanceOf(PythonAiClient.PythonAiException.class)
                .satisfies(e -> assertThat(((PythonAiClient.PythonAiException) e).upstreamStatus())
                        .isEqualTo(503));
        hold.countDown();
    }

    @Test
    @DisplayName("正常调用会穿过护栏（并且每次都有 trace id）")
    void passes_through_on_success() {
        assertThat(guard.call(() -> "ok")).isEqualTo("ok");
        assertThat(guard.currentTraceId()).isNotEqualTo("not-a-trace");
    }

    @Test
    @DisplayName("被隔离拒绝的请求不计入熔断失败率（否则一拥堵就误熔断）")
    void bulkhead_rejections_do_not_trip_breaker() throws Exception {
        CountDownLatch hold = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(2);
        for (int i = 0; i < 2; i++) {
            Thread t = new Thread(() -> guard.call(() -> {
                started.countDown();
                try {
                    hold.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                return "ok";
            }));
            t.setDaemon(true);
            t.start();
        }
        assertThat(started.await(3, TimeUnit.SECONDS)).isTrue();
        for (int i = 0; i < 4; i++) {
            try {
                guard.call(() -> "rejected");
            } catch (PythonAiClient.PythonAiException ignored) {
                // 预期：隔离拒绝
            }
        }
        assertThat(guard.breaker().getState()).isNotEqualTo(CircuitBreaker.State.OPEN);
        hold.countDown();
    }
}
