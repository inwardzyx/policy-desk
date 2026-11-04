package com.policydesk.api.health;

import com.policydesk.api.client.Dto;
import com.policydesk.api.client.PythonAiClient;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * 把"AI 服务通不通"接进 {@code /actuator/health}。
 *
 * <p>★ 为什么必须有它：默认的 {@code /actuator/health} 只回答"我这个进程活着吗"。
 * 而真正会让用户看到错误的分手是"Java 活着但 Python 挂了"—— 那时健康检查显示 UP，
 * 监控一片绿，用户全在报错。这条判据是<strong>在坏的情况下会响</strong>的那一类。
 *
 * <p>★ 它会被 {@code show-details: always} 展开成 {@code components.pythonAi}，
 * 于是 {@code curl /actuator/health | jq} 一眼能看到上游状态与块数。
 */
@Component
public class PythonAiHealthIndicator implements HealthIndicator {

    private final PythonAiClient ai;

    public PythonAiHealthIndicator(PythonAiClient ai) {
        this.ai = ai;
    }

    @Override
    public Health health() {
        try {
            Dto.Health h = ai.health();
            return Health.up()
                    .withDetail("chunks", h == null ? -1 : h.chunks())
                    .withDetail("rejected", h == null ? -1 : h.rejected())
                    .build();
        } catch (RuntimeException e) {
            // 不抛异常：健康检查抛异常会让整个 /actuator/health 变 500，
            // 那就分不清"它自己挂了"和"上游挂了"。
            return Health.down().withDetail("error", e.getMessage()).build();
        }
    }
}
