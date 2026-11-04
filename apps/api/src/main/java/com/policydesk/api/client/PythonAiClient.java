package com.policydesk.api.client;

import com.policydesk.api.config.PythonAiProperties;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.List;

/**
 * 唯一的出口：业务层只通过它跟 AI 服务说话。
 *
 * <p>★ 两个头就是这一层的全部契约：
 * <ul>
 *   <li>{@code X-Internal-Token} —— 证明"是 Java 在调"（Python 侧 fail-closed）；</li>
 *   <li>{@code X-Auth-Roles} —— 证明"是哪个用户、什么角色"（Python 侧按它做块级 ACL）。</li>
 * </ul>
 * ★ 特别注意 {@code X-Auth-Roles} 由**服务端**从登录态推出，绝不能让前端直接传过来：
 * 一旦它来自请求体/浏览器，等于把权限交给调用方（v1 的注释里写过同一条）。
 */
@Component
public class PythonAiClient {

    private final RestClient http;
    private final String internalToken;

    public PythonAiClient(RestClient pythonAiRestClient, PythonAiProperties props) {
        this.http = pythonAiRestClient;
        this.internalToken = props.internalToken();
    }

    /**
     * 问一次。
     *
     * @param question  用户问题
     * @param namespace 分区；null 表示默认库
     * @param roles     当前用户的角色（由登录态推出）
     */
    public Dto.ChatResponse chat(String question, String namespace, List<String> roles, String sessionId) {
        try {
            return http.post()
                    .uri("/internal/chat")
                    .header("X-Internal-Token", internalToken)
                    .header("X-Auth-Roles", String.join(",", roles))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new Dto.ChatRequest(question, namespace, sessionId))
                    .retrieve()
                    .body(Dto.ChatResponse.class);
        } catch (RestClientResponseException e) {
            // ★ 不把上游的原始 body 直接透给浏览器：里面可能带 Python 的栈信息。
            //   只保留状态码与一句可判断的摘要，原文进日志。
            throw new PythonAiException(e.getStatusCode().value(), e.getResponseBodyAsString(), e);
        }
    }

    /** 探活：给健康检查用（打 Python 的 {@code /health}）。 */
    public Dto.Health health() {
        try {
            return http.get().uri("/health").retrieve().body(Dto.Health.class);
        } catch (RestClientResponseException e) {
            throw new PythonAiException(e.getStatusCode().value(), e.getResponseBodyAsString(), e);
        }
    }

    /** 上游不可用 / 返回错误时抛这个；由 {@code ApiExceptionHandler} 翻成 502/504。 */
    public static class PythonAiException extends RuntimeException {
        private final int upstreamStatus;

        public PythonAiException(int upstreamStatus, String body, Throwable cause) {
            super("AI 服务返回 " + upstreamStatus + (body == null || body.isBlank() ? "" : "：" + body), cause);
            this.upstreamStatus = upstreamStatus;
        }

        public int upstreamStatus() {
            return upstreamStatus;
        }

        public HttpStatus asHttpStatus() {
            // 502 = 上游答了个错的；504 = 等不到（由超时异常单独映射）
            return HttpStatus.BAD_GATEWAY;
        }
    }
}
