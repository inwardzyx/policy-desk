package com.policydesk.api.web;

import com.policydesk.api.entity.AuditRecord;
import com.policydesk.api.security.JwtAuthFilter;
import com.policydesk.api.security.JwtService;
import com.policydesk.api.service.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 审计查询："我最近问了什么、结果如何"。
 *
 * <p>★ 返回体里**只有问题哈希，没有原文** —— 因为库里就没存原文。
 * 这一条既是隐私设计，也是免责证据：即使库被拖走，也还原不出用户问过什么。
 *
 * <p>★ 权限：admin 看全部，其他角色只看自己。默认拒绝（不传身份即 401），
 * 而不是"默认给全部"。
 */
@RestController
@RequestMapping("/api")
public class AuditController {

    private final AuditService audit;

    public AuditController(AuditService audit) {
        this.audit = audit;
    }

    public record Item(long id, String questionSha256, String namespace, String decision,
                       Integer nBlocks, Integer tookMs, String traceId, String createdAt) {
    }

    @GetMapping("/audit")
    public List<Item> recent(HttpServletRequest request,
                             @RequestParam(defaultValue = "20") int limit) {
        JwtService.Principal p = (JwtService.Principal) request.getAttribute(JwtAuthFilter.ATTR_PRINCIPAL);
        if (p == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.UNAUTHORIZED, "未认证");
        }
        boolean admin = p.roles().contains("admin");
        return audit.recent(p.userId(), admin, limit).stream()
                .map(AuditController::toItem)
                .toList();
    }

    private static Item toItem(AuditRecord r) {
        return new Item(r.getId(), r.getQuestionSha256(), r.getNamespace(), r.getDecision(),
                r.getNBlocks(), r.getTookMs(), r.getTraceId(),
                r.getCreatedAt() == null ? null : r.getCreatedAt().toString());
    }
}
