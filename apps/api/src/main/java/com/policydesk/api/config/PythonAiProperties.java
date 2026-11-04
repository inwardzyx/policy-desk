package com.policydesk.api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

/**
 * Python AI 服务的连接配置。
 *
 * <p>★ 为什么内部口令要在启动时校验（而不是等到第一次调用才报错）：
 * 它缺失时的现象是"用户问了问题才 500"，而那时你在演示、在面试、在排查业务逻辑。
 * 启动即失败，问题就被压到部署那一刻，成本低一个数量级（与 v1 的
 * {@code preflight} 同一条纪律：<b>能提前红，就别留到运行时</b>）。
 *
 * @param baseUrl           Python 服务地址，如 {@code http://127.0.0.1:8028}
 * @param internalToken     {@code X-Internal-Token} 的值，必须与 Python 侧 {@code INTERNAL_TOKEN} 一致
 * @param connectTimeoutMs  连接超时（毫秒）
 * @param readTimeoutMs     读超时（毫秒）。★ 给足：Python 那边一次问答要跑改写+精排+生成，
 *                          实测约 3 秒；这里的超时要大于它，否则"慢"会被记成"错"
 */
@ConfigurationProperties(prefix = "python-ai")
public record PythonAiProperties(
        String baseUrl,
        String internalToken,
        int connectTimeoutMs,
        int readTimeoutMs
) {

    public PythonAiProperties {
        if (!StringUtils.hasText(baseUrl)) {
            throw new IllegalStateException(
                    "python-ai.base-url 未配置：Java 层不知道去哪里找 AI 服务（示例：PYTHON_AI_URL=http://127.0.0.1:8028）");
        }
        if (!StringUtils.hasText(internalToken)) {
            // ★ fail-closed：没有口令就必须起不来。放行一次的代价是"公网裸奔"，见 v1 的 _internal_guard。
            throw new IllegalStateException(
                    "python-ai.internal-token 未配置：请设置环境变量 INTERNAL_TOKEN（与 Python 侧保持一致）");
        }
        if (connectTimeoutMs <= 0) {
            connectTimeoutMs = 2_000;
        }
        if (readTimeoutMs <= 0) {
            readTimeoutMs = 60_000;
        }
    }
}
