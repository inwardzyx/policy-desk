package com.policydesk.api.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.policydesk.api.entity.AuditRecord;
import com.policydesk.api.mapper.AuditMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;

/**
 * 审计：把"谁在什么时候问了什么（的哈希）、结果如何"落库。
 *
 * <p>★ 为什么写审计失败不能影响主流程：
 * 审计是**旁路证据**，不是业务前置条件。写不进去时应该报警（日志 WARN）但让用户拿到答案 ——
 * 反过来会让"审计库抖一下"变成"全站问答不可用"，这个取舍是有意的。
 * （对照：配额是**前置**条件，它失败必须拒绝，见 {@link QuotaService}。）
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final AuditMapper mapper;

    public AuditService(AuditMapper mapper) {
        this.mapper = mapper;
    }

    /** 决策取值：与查询/统计口径共用，写死在一处。 */
    public static final class Decision {
        public static final String ANSWERED = "answered";
        public static final String REFUSED = "refused";
        public static final String QUOTA_DENIED = "quota_denied";
        public static final String UPSTREAM_ERROR = "upstream_error";
        /**
         * agent 跑完但**没跑完整**：撞轮数上限 / 超 token 预算 / 没取证 / 工具失败。
         *
         * <p>★ 单独一档而不是并进 {@link #ANSWERED}：把"跑完了"和"跑残了"记成同一档，
         * 事后就查不出"哪一天开始退化的" —— 而这正是评测岗最常被问的那个问题。
         */
        public static final String DEGRADED = "degraded";
        /**
         * 流式路径：Java 只转发 SSE 字节，**不知道**循环最后是 done 还是 gate_refused。
         *
         * <p>★ 宁可记粗一档，也不把不知道的事情记成 "answered"。
         */
        public static final String STREAMED = "streamed";

        private Decision() {
        }
    }

    public void record(long userId, String question, String namespace, String decision,
                       Integer nBlocks, Integer tookMs, String traceId) {
        try {
            AuditRecord r = new AuditRecord();
            r.setUserId(userId);
            r.setQuestionSha256(sha256(question == null ? "" : question));
            r.setNamespace(namespace);
            r.setDecision(decision);
            r.setNBlocks(nBlocks);
            r.setTookMs(tookMs);
            r.setTraceId(traceId);
            r.setCreatedAt(LocalDateTime.now());
            mapper.insert(r);
        } catch (RuntimeException e) {
            // ★ 旁路证据，不阻断主流程；但必须留下痕迹，否则"审计静默失效"没人会发现。
            log.warn("写审计失败（不影响本次回答）：user={} decision={} err={}",
                    userId, decision, e.toString());
        }
    }

    /** 最近 N 条。admin 能看全部，普通用户只看自己。 */
    public List<AuditRecord> recent(long userId, boolean admin, int limit) {
        LambdaQueryWrapper<AuditRecord> w = new LambdaQueryWrapper<>();
        if (!admin) {
            w.eq(AuditRecord::getUserId, userId);
        }
        w.orderByDesc(AuditRecord::getId).last("LIMIT " + Math.max(1, Math.min(limit, 100)));
        return mapper.selectList(w);
    }

    /** 问题哈希：同一个问题两次提问能对上，但库里的哈希还原不出原文。 */
    public static String sha256(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 必备算法，走到这里说明运行环境坏了
            throw new IllegalStateException("JVM 缺少 SHA-256", e);
        }
    }
}
