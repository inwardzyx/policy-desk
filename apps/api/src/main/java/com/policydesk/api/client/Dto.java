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

    /** agent 的请求。**故意比 ChatRequest 小**：检索参数由模型决定，不由调用方决定。 */
    public record AgentRequest(String question, Integer max_rounds, Integer budget_tokens) {
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

    /**
     * agent 的一步工具调用。
     *
     * <p>★ `status` 三态：`hit` / `no_hit` / `null`（解析不了**不猜**）。
     * 前端把它画成绿/黄/灰三种点，而不是"没有就是成功"。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AgentStep(
            int i,
            String tool,
            java.util.Map<String, Object> args,
            int tool_ms,
            String status,
            int n_src,
            String error
    ) {
    }

    /**
     * agent 的响应。
     *
     * <p>★ `stopped_reason` 是**必读字段**，不是装饰：`done` 之外的一切都意味着
     * "这段答案不是完整跑完的产物"（撞上限 / 超预算 / 没取证 / 工具失败）。
     * Java 不解释它，原样带给前端 —— 语义只在一处定义（Python 的 `agent/loop.py`）。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AgentResponse(
            String answer,
            List<Source> sources,
            List<AgentStep> steps,
            String stopped_reason,
            int rounds,
            int tokens,
            boolean refused,
            int took_ms
    ) {
    }

    /** Python 的 {@code /health}：用来做健康检查探针。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Health(int chunks, int rejected) {
    }
}
