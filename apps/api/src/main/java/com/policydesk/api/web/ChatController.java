package com.policydesk.api.web;

import com.policydesk.api.client.Dto;
import com.policydesk.api.client.PythonAiClient;
import jakarta.validation.constraints.NotBlank;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;

/**
 * 问答入口（M2 的最小版：一条路由，证明"前端 → Java → Python"这条链路通）。
 *
 * <p>★ 角色从哪来（M2 与 M3 的分界）：
 * 这里暂时读 {@code X-Demo-Roles} 头，**只用于打通链路**。M3 接上登录后必须换成
 * 从 JWT 里取 —— 而且那时要把这个头在网关/过滤器上**直接删掉**，否则它会成为
 * 一条绕过登录的提权路径。{@code policy-desk.demo-roles-header=false} 可以关掉它，
 * 生产部署必须关。
 */
@RestController
@RequestMapping("/api")
public class ChatController {

    private final PythonAiClient ai;
    private final boolean demoRolesHeader;

    public ChatController(PythonAiClient ai,
                          @Value("${policy-desk.demo-roles-header:true}") boolean demoRolesHeader) {
        this.ai = ai;
        this.demoRolesHeader = demoRolesHeader;
    }

    public record AskBody(@NotBlank String question, String namespace, String sessionId) {
    }

    @PostMapping("/chat")
    public Dto.ChatResponse chat(
            @RequestBody @jakarta.validation.Valid AskBody body,
            @RequestHeader(value = "X-Demo-Roles", required = false) String demoRoles) {
        return ai.chat(body.question(), body.namespace(), resolveRoles(demoRoles), body.sessionId());
    }

    private List<String> resolveRoles(String demoRoles) {
        if (!demoRolesHeader || demoRoles == null || demoRoles.isBlank()) {
            return List.of("public");
        }
        return Arrays.stream(demoRoles.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
