package com.policydesk.api.client;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 调 AI 服务的韧性护栏：熔断 + 并发隔离 + trace id。
 *
 * <p>★ 为什么只做这三件事（2026-11-20 的取舍）：
 * <ul>
 *   <li>服务注册/配置中心/网关在本项目是**负收益**：只有两个服务、一台机器，
 *       Compose 服务名就是名字解析、Nginx 就是网关、.env 就是配置；</li>
 *   <li>真正会咬人的是「AI 服务挂了/变慢时，Java 这边怎么表现」——
 *       没有护栏时表现为请求一直挂着、线程池被占满、其他接口一起变慢；</li>
 *   <li>所以只补三样，且每样都能被故障注入验证：熔断（快速失败）、隔离（不互相拖垮）、
 *       trace id（跨进程能把两个服务的日志串起来）。</li>
 * </ul>
 *
 * <p>★ 熔断参数为什么这么定：窗口 4 次、失败率 50%、打开 30 秒 —— 上游是**同机容器**，
 * 故障通常是"进程没了/卡住"，短窗口能更快打开、30 秒足够重启一个容器。
 * 参数写在代码里而不是配置中心：现在的环境变量只有 5 个，多一层配置只会多一处不同步。
 */
@Component
public class AiGuard {

    private static final Logger log = LoggerFactory.getLogger(AiGuard.class);

    /** 熔断：最近 4 次里失败过半就打开，30 秒后放一个请求试探（半开）。 */
    private final CircuitBreaker breaker = CircuitBreaker.of("python-ai",
            CircuitBreakerConfig.custom()
                    .slidingWindowSize(4)
                    .minimumNumberOfCalls(4)
                    .failureRateThreshold(50.0f)
                    .waitDurationInOpenState(Duration.ofSeconds(30))
                    .permittedNumberOfCallsInHalfOpenState(1)
                    .build());

    /** 隔离：最多 2 个并发调 AI；满了立刻拒绝，而不是排队把线程占住。 */
    private final Bulkhead bulkhead = Bulkhead.of("python-ai",
            BulkheadConfig.custom()
                    .maxConcurrentCalls(2)
                    .maxWaitDuration(Duration.ZERO)
                    .build());

    /** 本次调用的 trace id：两个服务日志里都打它，才能把一次请求串起来。 */
    private final ThreadLocal<String> traceId = new ThreadLocal<>();

    public String currentTraceId() {
        String id = traceId.get();
        return id != null ? id : "-";
    }

    /**
     * 执行一次上游调用。
     *
     * <p>★ 顺序是「隔离在外、熔断在内」：先限制并发（不让线程堆起来），
     * 再让熔断器统计失败率（被隔离拒绝的请求不应计入上游失败，否则一拥堵就误熔断）。
     */
    public <T> T call(Supplier<T> upstream) {
        String id = UUID.randomUUID().toString().substring(0, 8);
        traceId.set(id);
        try {
            return bulkhead.executeSupplier(() -> breaker.executeSupplier(() -> {
                Supplier<T> s = upstream;
                return s.get();
            }));
        } catch (BulkheadFullException e) {
            log.warn("[trace={}] 并发隔离已满（max=2），快速拒绝", id);
            throw new PythonAiClient.PythonAiException(503, "并发隔离已满：同时调 AI 的请求超过 2 个", e);
        } catch (CallNotPermittedException e) {
            log.warn("[trace={}] 熔断器已打开，快速失败", id);
            throw new PythonAiClient.PythonAiException(503, "熔断中：AI 服务近期连续失败，30 秒后重试", e);
        } finally {
            traceId.remove();
        }
    }

    // 只给测试与观测用
    public CircuitBreaker breaker() {
        return breaker;
    }

    public Bulkhead bulkhead() {
        return bulkhead;
    }
}
