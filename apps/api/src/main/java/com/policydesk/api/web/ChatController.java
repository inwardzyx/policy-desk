package com.policydesk.api.web;

import com.policydesk.api.client.Dto;
import com.policydesk.api.security.AclProjector;
import com.policydesk.api.security.JwtAuthFilter;
import com.policydesk.api.security.JwtService;
import com.policydesk.api.service.ChatService;
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
 * 问答入口（只做三件事：取身份、算 ACL、交给 {@link ChatService}）。
 *
 * <p>★ 这条链路上"谁能看什么"只经过一处转换：
 * <pre>
 *   JWT 里的 roles ──AclProjector──▶ ACL ──X-Auth-Roles──▶ Python（按块过滤）
 * </pre>
 * 控制器**不自己算 ACL**，也不把 roles 直接透传 —— 否则映射逻辑会散落在每个接口里。
 */
@RestController
@RequestMapping("/api")
public class ChatController {

    private final ChatService chat;
    private final AclProjector projector;
    private final boolean demoRolesHeader;

    public ChatController(ChatService chat, AclProjector projector,
                          @org.springframework.beans.factory.annotation.Value(
                                  "${policy-desk.demo-roles-header:false}") boolean demoRolesHeader) {
        this.chat = chat;
        this.projector = projector;
        this.demoRolesHeader = demoRolesHeader;
    }

    public record AskBody(@NotBlank String question, String namespace, String sessionId) {
    }

    @PostMapping("/chat")
    public Dto.ChatResponse chat(@RequestBody @Valid AskBody body, HttpServletRequest request) {
        JwtService.Principal p = (JwtService.Principal) request.getAttribute(JwtAuthFilter.ATTR_PRINCIPAL);
        long userId = p != null ? userIdOf(p) : demoUserId();
        return chat.ask(userId, resolveAcl(request, p), body.question(), body.namespace(), body.sessionId());
    }

    /**
     * agent 问答（非流式）。与 {@code /chat} 共用同一套身份与 ACL 解析 —— **不新造一套**。
     *
     * <p>★ 请求体只有 `question`：`max_rounds` / `budget_tokens` 是循环参数，
     * 属于"运维旋钮"，不给浏览器传（否则前端一改就能让一次问答烧掉几万 token）。
     * 要调就改 Python 侧默认值或环境变量，那是有审计的路径。
     */
    public record AgentBody(@NotBlank String question) {
    }

    @PostMapping("/agent")
    public Dto.AgentResponse agent(@RequestBody @Valid AgentBody body, HttpServletRequest request) {
        JwtService.Principal p = (JwtService.Principal) request.getAttribute(JwtAuthFilter.ATTR_PRINCIPAL);
        long userId = p != null ? userIdOf(p) : demoUserId();
        return chat.agent(userId, resolveAcl(request, p), body.question());
    }

    /**
     * agent 流式问答（SSE 穿透）。
     *
     * <p>★ 职责只有一个：**把字节搬过去**。事件的语义（plan/tool_call/tool_result/answer）
     * 只在 Python 一处定义；Java 解析 = 第二套判据。
     * <p>★ `StreamingResponseBody` 跑在 MVC 的异步线程里，所以 `try-with-resources` 必须在
     * lambda 内部 —— 提前关流会让浏览器收到半截 SSE（而且看起来像"模型卡住了"）。
     */
    @PostMapping("/agent/stream")
    public org.springframework.http.ResponseEntity<org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody>
            agentStream(@RequestBody @Valid AgentBody body, HttpServletRequest request) {
        JwtService.Principal p = (JwtService.Principal) request.getAttribute(JwtAuthFilter.ATTR_PRINCIPAL);
        long userId = p != null ? userIdOf(p) : demoUserId();
        java.io.InputStream in = chat.agentStream(userId, resolveAcl(request, p), body.question());
        org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody stream = out -> {
            try (in) {
                in.transferTo(out);
                out.flush();
            }
        };
        return org.springframework.http.ResponseEntity.ok()
                .contentType(org.springframework.http.MediaType.TEXT_EVENT_STREAM)
                .header("Cache-Control", "no-cache")
                .header("X-Accel-Buffering", "no")
                .body(stream);
    }

    /**
     * 解析这次请求的 ACL。
     *
     * <p>顺序有讲究：**已认证 > 联调头 > 拒绝**。
     * 若把联调头放前面，一个已登录的 employee 只要多带一个头就能变成 hr。
     */
    private List<String> resolveAcl(HttpServletRequest request, JwtService.Principal p) {
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

    private long userIdOf(JwtService.Principal p) {
        return p.userId();
    }

    /** 联调模式下没有登录用户：给一个固定的负数 id，让审计能区分"演示流量"。 */
    private long demoUserId() {
        return -1L;
    }
}
