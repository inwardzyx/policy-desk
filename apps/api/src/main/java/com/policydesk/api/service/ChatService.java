package com.policydesk.api.service;

import com.policydesk.api.client.Dto;
import com.policydesk.api.client.PythonAiClient;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 一次问答的完整业务动作：**配额 → 调 AI → 审计**。
 *
 * <p>★ 为什么把这三步收在一个 service 里，而不是写在 controller：
 * 它们有严格顺序，而且有两条退出路径（配额拒、上游错）都必须留下审计。
 * 散在 controller 的各分支里，一定会漏掉某条 —— v1 那边就因为"记账写在 return 前面"
 * 漏掉了异常路径（README 里记着那次）。这里用 try/catch 把"每条路都记一笔"钉死。
 */
@Service
public class ChatService {

    private final PythonAiClient ai;
    private final QuotaService quota;
    private final AuditService audit;

    public ChatService(PythonAiClient ai, QuotaService quota, AuditService audit) {
        this.ai = ai;
        this.quota = quota;
        this.audit = audit;
    }

    /** 配额用尽。带上是第几次、上限多少，方便前端提示"明天再来"。 */
    public static class QuotaExceeded extends RuntimeException {
        private final QuotaService.Decision decision;

        public QuotaExceeded(QuotaService.Decision decision) {
            super("今日配额已用尽（" + decision.used() + "/" + decision.limit() + "）");
            this.decision = decision;
        }

        public QuotaService.Decision decision() {
            return decision;
        }
    }

    public Dto.ChatResponse ask(long userId, List<String> acl, String question,
                                String namespace, String sessionId) {
        // ① 配额：**在调用模型之前**。被拒的请求不产生任何 token 费用。
        QuotaService.Decision q = quota.consume(userId);
        if (!q.allowed()) {
            audit.record(userId, question, namespace, AuditService.Decision.QUOTA_DENIED,
                    null, null, null);
            throw new QuotaExceeded(q);
        }

        // ② 调 AI。上游出错也要留痕 —— 否则"今天怎么这么多 502"查不出来。
        try {
            Dto.ChatResponse r = ai.chat(question, namespace, acl, sessionId);
            String decision = r.knowledge_hit()
                    ? AuditService.Decision.ANSWERED
                    : AuditService.Decision.REFUSED;
            audit.record(userId, question, namespace, decision,
                    r.sources() == null ? 0 : r.sources().size(), r.took_ms(), traceIdOf(r));
            return r;
        } catch (PythonAiClient.PythonAiException e) {
            audit.record(userId, question, namespace, AuditService.Decision.UPSTREAM_ERROR,
                    null, null, null);
            throw e;
        }
    }

    /** 从 Python 的响应里取 trace_id（namespace 路径才有；拿不到就记 null，不补假值）。 */
    private String traceIdOf(Dto.ChatResponse r) {
        return r.context() == null ? null : r.context().trace_id();
    }
}
