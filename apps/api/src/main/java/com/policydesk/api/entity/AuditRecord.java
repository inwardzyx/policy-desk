package com.policydesk.api.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 一次访问的审计记录。
 *
 * <p>★ 只存"访问事实"，不存内容：
 * <ul>
 *   <li>问题只存 <b>sha256</b>（能比对同一问题出现几次，但还原不出原文 —— PII 不进库）；</li>
 *   <li>答案正文不存（它在 Python 侧与响应里，库不是给它的地方）；</li>
 *   <li>存 {@code decision}：被拒也要留痕，否则"谁在被刷"完全看不见。</li>
 * </ul>
 */
@TableName("audit")
public class AuditRecord {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String questionSha256;
    private String namespace;
    private String decision;
    private Integer nBlocks;
    private Integer tookMs;
    private String traceId;
    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getQuestionSha256() {
        return questionSha256;
    }

    public void setQuestionSha256(String questionSha256) {
        this.questionSha256 = questionSha256;
    }

    public String getNamespace() {
        return namespace;
    }

    public void setNamespace(String namespace) {
        this.namespace = namespace;
    }

    public String getDecision() {
        return decision;
    }

    public void setDecision(String decision) {
        this.decision = decision;
    }

    public Integer getNBlocks() {
        return nBlocks;
    }

    public void setNBlocks(Integer nBlocks) {
        this.nBlocks = nBlocks;
    }

    public Integer getTookMs() {
        return tookMs;
    }

    public void setTookMs(Integer tookMs) {
        this.tookMs = tookMs;
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
