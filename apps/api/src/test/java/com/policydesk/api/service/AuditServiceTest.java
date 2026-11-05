package com.policydesk.api.service;

import com.policydesk.api.entity.AuditRecord;
import com.policydesk.api.mapper.AuditMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 审计判据：**原文不进库**、决策要落、写失败不能炸主流程。
 */
class AuditServiceTest {

    private final AuditMapper mapper = mock(AuditMapper.class);
    private final AuditService audit = new AuditService(mapper);

    @Test
    @DisplayName("问题只存 sha256：64 位十六进制，且库里没有原文")
    void stores_hash_not_plaintext() {
        audit.record(7L, "加班费怎么算？", "company", AuditService.Decision.ANSWERED, 1, 42, "t-1");

        ArgumentCaptor<AuditRecord> captor = ArgumentCaptor.forClass(AuditRecord.class);
        verify(mapper).insert(captor.capture());
        AuditRecord saved = captor.getValue();

        assertThat(saved.getQuestionSha256())
                .hasSize(64)
                .matches("[0-9a-f]{64}")
                .isNotEqualTo("加班费怎么算？");
        assertThat(saved.getDecision()).isEqualTo("answered");
        assertThat(saved.getUserId()).isEqualTo(7L);
        assertThat(saved.getTraceId()).isEqualTo("t-1");
    }

    @Test
    @DisplayName("同一个问题两次 ⇒ 同一个哈希（能比对频次，但还原不出内容）")
    void same_question_same_hash() {
        assertThat(AuditService.sha256("同一个问题"))
                .isEqualTo(AuditService.sha256("同一个问题"));
        assertThat(AuditService.sha256("同一个问题"))
                .isNotEqualTo(AuditService.sha256("同一个问题？"));
    }

    @Test
    @DisplayName("写库失败 ⇒ 吞掉异常（旁路证据，不能让一次库抖动变成全站不可用）")
    void db_failure_is_swallowed() {
        // ★ 必须写 any(AuditRecord.class)：BaseMapper 同时有 insert(T) 与 insert(Collection<T>)，
        //   裸 any() 会让编译器无法在两个重载之间选择。
        when(mapper.insert(any(AuditRecord.class))).thenThrow(new RuntimeException("db down"));

        // 不抛就是通过
        audit.record(1L, "q", null, AuditService.Decision.ANSWERED, 0, 1, null);
    }
}
