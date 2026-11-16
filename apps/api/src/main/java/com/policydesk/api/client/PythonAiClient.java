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
    private final String baseUrl;

    public PythonAiClient(RestClient pythonAiRestClient, PythonAiProperties props) {
        this.http = pythonAiRestClient;
        this.internalToken = props.internalToken();
        this.baseUrl = props.baseUrl();
    }

    /**
     * 问一次。
     *
     * @param question  用户问题
     * @param namespace 分区；null 表示默认库
     * @param acl       当前用户**可读的 ACL 集合**（由 {@code AclProjector} 从角色投影而来）。
     *                  ★ 注意它发的头叫 {@code X-Auth-Roles} —— 那是 Python 侧既有的头名，
     *                  语义是"这次请求带的权限标记"，和块里的 {@code chunk.acl} 是同一套词表。
     *                  改名会让 v1 的认证路径失效（且它已有测试），所以保头名、改语义说明。
     */
    public Dto.ChatResponse chat(String question, String namespace, List<String> acl, String sessionId) {
        try {
            return http.post()
                    .uri("/internal/chat")
                    .header("X-Internal-Token", internalToken)
                    .header("X-Auth-Roles", String.join(",", acl))
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

    /**
     * 走 agent 循环问一次（非流式）。
     *
     * <p>★ 与 {@link #chat} 的两个差别，都是有意的：
     * <ul>
     *   <li>打 {@code /agent} 而不是 {@code /internal/chat}：agent 循环住在 Python 侧，
     *       Java 不复制它的判断（重写 = 第二套行为）；</li>
     *   <li>**照旧带 {@code X-Auth-Roles}**：agent 自己决定调哪个工具，但权限不是模型的事 ——
     *       漏了这个头，检索就不按 ACL 过滤，而且不会报错（最坏的那种失败）。</li>
     * </ul>
     */
    public Dto.AgentResponse agent(String question, List<String> acl) {
        try {
            return http.post()
                    .uri("/agent")
                    .header("X-Internal-Token", internalToken)
                    .header("X-Auth-Roles", String.join(",", acl))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new Dto.AgentRequest(question, null, null))
                    .retrieve()
                    .body(Dto.AgentResponse.class);
        } catch (RestClientResponseException e) {
            throw new PythonAiException(e.getStatusCode().value(), e.getResponseBodyAsString(), e);
        }
    }

    /**
     * agent 流式问答：把 Python 的 SSE 字节流**原样**交给调用方（不解析、不重排）。
     *
     * <p>★ 为什么不用 `RestClient.exchange`：它回调里拿到的 `InputStream` **一出版就关**
     * （实测 `java.io.IOException: stream is closed`，两次），因为它属于那一次请求的作用域。
     * 用 JDK 的 `HttpClient.send(..., BodyHandlers.ofInputStream())`：返回的流是**活的**，
     * 可以交给另一个线程慢慢读 —— 这才是 SSE 穿透该用的东西。
     * <p>★ 事件语义（plan / tool_call / tool_result / answer / done）只在 Python 一处定义，
     * Java 不解析：多一套解析 = 多一套"什么算一步"的判据。
     */
    public java.io.InputStream streamAgent(String question, List<String> acl) {
        try {
            String body = java.util.Objects.requireNonNull(
                    new com.fasterxml.jackson.databind.ObjectMapper()
                            .writeValueAsString(new Dto.AgentRequest(question, null, null)));
            java.net.http.HttpRequest req = java.net.http.HttpRequest
                    .newBuilder(java.net.URI.create(baseUrl + "/agent/stream"))
                    .header("X-Internal-Token", internalToken)
                    .header("X-Auth-Roles", String.join(",", acl))
                    .header("Content-Type", "application/json")
                    .timeout(java.time.Duration.ofSeconds(300))
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body))
                    .build();
            java.net.http.HttpResponse<java.io.InputStream> resp = STREAM_HTTP.send(
                    req, java.net.http.HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() / 100 != 2) {
                try (java.io.InputStream err = resp.body()) {
                    String detail = new String(err.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                    throw new PythonAiException(resp.statusCode(), detail, null);
                }
            }
            return resp.body();
        } catch (java.io.IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new PythonAiException(504, e.toString(), e);
        }
    }

    /** 流式专用客户端：连接超时短、读超时长（SSE 会安静地等模型）。 */
    private static final java.net.http.HttpClient STREAM_HTTP =
            java.net.http.HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(5))
                    .build();

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
