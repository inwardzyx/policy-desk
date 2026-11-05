package com.policydesk.api.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * 与 Python 服务之间的**契约**（只声明我们真的会用的字段）。
 *
 * <p>★ 一律 `ignoreUnknown = true`：Python 侧的响应会加字段（v1 已经加过
 * {@code session_used}、{@code context}、{@code citation}），Java 侧不该因为上游多加一个字段就炸。
 * 反过来（Java 少读一个字段）是安全的：这里只放"业务层要展示/要落库"的部分。
 */
public final class Dto {

    private Dto() {
    }

    /** 发出去的请求。★ `acl` 不在里面：角色由 {@code X-Auth-Roles} 头带过去，见 PythonAiClient。 */
    public record ChatRequest(String question, String namespace, String sessionId) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Source(String doc, String text, Double score, Integer rerank_score, String reason) {
    }

    /**
     * Python 的上下文摘要（只取我们真的会用的 trace_id）。
     *
     * <p>★ trace_id 是"这次请求在 Python 侧的唯一编号"，审计靠它把两边的记录对上。
     * 它**只在带 namespace 的请求上存在**，所以可能为 null —— 不补假值。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Context(String trace_id) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ChatResponse(
            String answer,
            List<Source> sources,
            int took_ms,
            boolean rerank_used,
            boolean knowledge_hit,
            Context context
    ) {
    }

    /** Python 的 {@code /health}：用来做健康检查探针。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Health(int chunks, int rejected) {
    }
}
