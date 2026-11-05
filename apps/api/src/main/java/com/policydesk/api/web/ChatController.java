package com.policydesk.api.web;

import com.policydesk.api.client.Dto;
import com.policydesk.api.client.PythonAiClient;
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

import java.util.List;

/**
 * 问答入口。
 *
 * <p>★ 角色从哪来（M3 之后）：<b>只从中认证后的请求属性</b>取，
 * 而那个属性只由 {@link JwtAuthFilter} 在校验通过后写入。
 * 于是"角色"这条链路上只有一个可信来源 —— 前端、请求体、任何自定义头都不能影响它。
 *
 * <p>★ 上一版（M2）用 {@code X-Demo-Roles} 头传角色，那是一条**绕过登录的提权路径**：
 * 现在它默认关闭，只在 {@code policy-desk.demo-roles-header=true} 时作为联调后备，
 * 并且**一旦请求已认证就绝不采信**（已认证的用户不能被一个头改写角色）。
 */
@RestController
@RequestMapping("/api")
public class ChatController {

    private final PythonAiClient ai;
    private final boolean demoRolesHeader;

    public ChatController(PythonAiClient ai,
                          @org.springframework.beans.factory.annotation.Value(
                                  "${policy-desk.demo-roles-header:false}") boolean demoRolesHeader) {
        this.ai = ai;
        this.demoRolesHeader = demoRolesHeader;
    }

    public record AskBody(@NotBlank String question, String namespace, String sessionId) {
    }

    @PostMapping("/chat")
    public Dto.ChatResponse chat(@RequestBody @Valid AskBody body, HttpServletRequest request) {
        List<String> roles = resolveRoles(request);
        return ai.chat(body.question(), body.namespace(), roles, body.sessionId());
    }

    /**
     * 解析这次请求的角色。
     *
     * <p>顺序是有讲究的：**已认证 > 联调头 > 匿名**。
     * 如果把联调头放在前面，一个已登录的 employee 只要多带一个头就能变成 hr。
     */
    private List<String> resolveRoles(HttpServletRequest request) {
        JwtService.Principal p = (JwtService.Principal) request.getAttribute(JwtAuthFilter.ATTR_PRINCIPAL);
        if (p != null) {
            return p.roles();
        }
        if (demoRolesHeader) {
            String demo = request.getHeader("X-Demo-Roles");
            if (demo != null && !demo.isBlank()) {
                return java.util.Arrays.stream(demo.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .toList();
            }
        }
        // 匿名请求：**不猜、不继承**，一律只给 public。
        // 要不要允许匿名提问由业务决定；这里选择"允许但只看得见 public 条款"。
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                "未认证：请先登录（POST /api/auth/login 拿令牌，再带 Authorization: Bearer ...）");
    }
}
