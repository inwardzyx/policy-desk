package com.policydesk.api.web;

import com.policydesk.api.client.Dto;
import com.policydesk.api.client.PythonAiClient;
import com.policydesk.api.security.AclProjector;
import com.policydesk.api.security.JwtAuthFilter;
import com.policydesk.api.security.JwtService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Arrays;
import java.util.List;

/**
 * 问答入口。
 *
 * <p>★ 这条链路上"谁能看什么"只经过一处转换：
 * <pre>
 *   JWT 里的 roles ──AclProjector──▶ ACL ──X-Auth-Roles──▶ Python（按块过滤）
 * </pre>
 * 控制器**不自己算 ACL**，也不把 roles 直接透传 —— 否则映射逻辑会散落在每个接口里，
 * 改一处漏一处。
 */
@RestController
@RequestMapping("/api")
public class ChatController {

    private final PythonAiClient ai;
    private final AclProjector projector;
    private final boolean demoRolesHeader;

    public ChatController(PythonAiClient ai, AclProjector projector,
                          @org.springframework.beans.factory.annotation.Value(
                                  "${policy-desk.demo-roles-header:false}") boolean demoRolesHeader) {
        this.ai = ai;
        this.projector = projector;
        this.demoRolesHeader = demoRolesHeader;
    }

    public record AskBody(@NotBlank String question, String namespace, String sessionId) {
    }

    @PostMapping("/chat")
    public Dto.ChatResponse chat(@RequestBody @Valid AskBody body, HttpServletRequest request) {
        return ai.chat(body.question(), body.namespace(), resolveAcl(request), body.sessionId());
    }

    /**
     * 解析这次请求的 ACL。
     *
     * <p>顺序有讲究：**已认证 > 联调头 > 拒绝**。
     * 若把联调头放前面，一个已登录的 employee 只要多带一个头就能变成 hr。
     */
    private List<String> resolveAcl(HttpServletRequest request) {
        JwtService.Principal p = (JwtService.Principal) request.getAttribute(JwtAuthFilter.ATTR_PRINCIPAL);
        if (p != null) {
            return projector.project(p.roles());
        }
        if (demoRolesHeader) {
            String demo = request.getHeader("X-Demo-Roles");
            if (demo != null && !demo.isBlank()) {
                List<String> roles = Arrays.stream(demo.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .toList();
                return projector.project(roles);
            }
        }
        // 匿名请求：**不猜、不继承**。要匿名提问就先显式放开一个 public 只读接口，
        // 而不是让"没带令牌"悄悄等于"public 权限"。
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                "未认证：请先登录（POST /api/auth/login 拿令牌，再带 Authorization: Bearer ...）");
    }
}
