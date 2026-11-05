package com.policydesk.api.service;

import com.policydesk.api.client.Dto;
import com.policydesk.api.client.PythonAiClient;
import com.policydesk.api.quota.InMemoryQuotaStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 编排判据：**配额 → 调 AI → 审计**，三条路都要留痕。
 *
 * <p>★ 这一层最容易出的错是"某条分支忘了记审计"：
 * 比如配额拒了直接抛异常、没有落库 —— 于是"谁在被刷"完全看不见。
 * 三条用例各走一条路，逐条断言"审计记了什么、AI 有没有被调用"。
 */
class ChatServiceTest {

    private final PythonAiClient ai = mock(PythonAiClient.class);
    private final AuditService audit = mock(AuditService.class);
    private final InMemoryQuotaStore store = new InMemoryQuotaStore();
    private final QuotaService quota = new QuotaService(store, 2,
            Clock.fixed(Instant.parse("2026-11-05T10:00:00Z"), ZoneId.of("Asia/Shanghai")));
    private final ChatService chat = new ChatService(ai, quota, audit);

    private Dto.ChatResponse response(boolean hit) {
        return new Dto.ChatResponse("答案", List.of(), 42, true, hit,
                new Dto.Context("trace-abc"));
    }

    @Test
    @DisplayName("正常回答 ⇒ 审计 answered，带块数与 trace_id")
    void answered_is_audited() {
        when(ai.chat(any(), any(), any(), any())).thenReturn(response(true));

        chat.ask(7L, List.of("public"), "q", "ns", null);

        verify(audit).record(eq(7L), eq("q"), eq("ns"), eq(AuditService.Decision.ANSWERED),
                eq(0), eq(42), eq("trace-abc"));
    }

    @Test
    @DisplayName("库外拒答 ⇒ 审计 refused（不是 answered —— 这两件事要分开统计）")
    void refused_is_audited_as_refused() {
        when(ai.chat(any(), any(), any(), any())).thenReturn(response(false));

        chat.ask(7L, List.of("public"), "q", null, null);

        verify(audit).record(eq(7L), eq("q"), isNull(), eq(AuditService.Decision.REFUSED),
                eq(0), eq(42), eq("trace-abc"));
    }

    @Test
    @DisplayName("配额用尽 ⇒ **不调 AI**（不烧 token），且审计记 quota_denied")
    void quota_denied_does_not_call_ai() {
        when(ai.chat(any(), any(), any(), any())).thenReturn(response(true));
        chat.ask(7L, List.of("public"), "q1", null, null);
        chat.ask(7L, List.of("public"), "q2", null, null);

        assertThatThrownBy(() -> chat.ask(7L, List.of("public"), "q3", null, null))
                .isInstanceOf(ChatService.QuotaExceeded.class);

        verify(ai, times(2)).chat(any(), any(), any(), any());   // 只调过 2 次，第 3 次被配额拦住
        verify(audit).record(eq(7L), eq("q3"), isNull(),
                eq(AuditService.Decision.QUOTA_DENIED), isNull(), isNull(), isNull());
    }

    @Test
    @DisplayName("上游出错 ⇒ 审计 upstream_error，异常继续往上抛（由 advice 翻成 502/504）")
    void upstream_error_is_audited_and_rethrown() {
        when(ai.chat(any(), any(), any(), any()))
                .thenThrow(new PythonAiClient.PythonAiException(500, "boom", null));

        assertThatThrownBy(() -> chat.ask(7L, List.of("public"), "q", null, null))
                .isInstanceOf(PythonAiClient.PythonAiException.class);

        verify(audit).record(eq(7L), eq("q"), isNull(),
                eq(AuditService.Decision.UPSTREAM_ERROR), isNull(), isNull(), isNull());
    }

    @Test
    @DisplayName("响应里没有 context（docs 路径）⇒ trace_id 记 null，不补假值")
    void missing_context_gives_null_trace_id() {
        when(ai.chat(any(), any(), any(), any()))
                .thenReturn(new Dto.ChatResponse("答案", List.of(), 5, false, true, null));

        chat.ask(7L, List.of("public"), "q", null, null);

        verify(audit).record(eq(7L), eq("q"), isNull(), eq(AuditService.Decision.ANSWERED),
                eq(0), eq(5), isNull());
        assertThat(true).isTrue();
    }
}
