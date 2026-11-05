package com.policydesk.api.web;

import com.policydesk.api.client.Dto;
import com.policydesk.api.client.PythonAiClient;
import com.policydesk.api.security.AclProjector;
import com.policydesk.api.security.JwtAuthFilter;
import com.policydesk.api.security.JwtService;
import com.policydesk.api.service.ChatService;
import com.policydesk.api.service.QuotaService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 控制器判据：**身份 → ACL** 的转换，以及各种失败的状态码。
 *
 * <p>★ 这里最值钱的两条：
 * <ul>
 *   <li>「发给业务层的是 ACL 而不是角色名」—— 防止哪天有人图省事把 roles 直接透传；</li>
 *   <li>「已认证时 X-Demo-Roles 不生效」—— M2 → M3 那次提权洞的回归。</li>
 * </ul>
 */
@WebMvcTest(ChatController.class)
@Import(AclProjector.class)
class ChatControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ChatService chat;

    @MockitoBean
    private JwtService jwt;

    private static JwtService.Principal principal(long id, String... roles) {
        return new JwtService.Principal(id, "someone", List.of(roles));
    }

    private static Dto.ChatResponse ok() {
        return new Dto.ChatResponse("按 1.5 倍计算", List.of(), 12, true, true, null);
    }

    @Test
    @DisplayName("hr 角色 ⇒ 传给业务层的是 ACL「hr,public」，且带上 userId")
    void hr_role_is_projected_to_acl() throws Exception {
        when(chat.ask(anyLong(), any(), any(), any(), any())).thenReturn(ok());

        mvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"加班费怎么算\"}")
                        .requestAttr(JwtAuthFilter.ATTR_PRINCIPAL, principal(7L, "hr")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("按 1.5 倍计算"));

        verify(chat).ask(eq(7L), eq(List.of("hr", "public")), eq("加班费怎么算"), isNull(), isNull());
    }

    @Test
    @DisplayName("employee 角色 ⇒ 只有 public（拿不到 hr 条款）")
    void employee_role_gets_public_only() throws Exception {
        when(chat.ask(anyLong(), any(), any(), any(), any())).thenReturn(ok());

        mvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"q\"}")
                        .requestAttr(JwtAuthFilter.ATTR_PRINCIPAL, principal(9L, "employee")))
                .andExpect(status().isOk());

        verify(chat).ask(eq(9L), eq(List.of("public")), eq("q"), isNull(), isNull());
    }

    @Test
    @DisplayName("角色没登记 ⇒ 403，且**不调用业务层**")
    void unknown_role_is_403_without_calling_service() throws Exception {
        mvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"q\"}")
                        .requestAttr(JwtAuthFilter.ATTR_PRINCIPAL, principal(1L, "HRR")))
                .andExpect(status().isForbidden());

        verifyNoInteractions(chat);
    }

    @Test
    @DisplayName("匿名 ⇒ 401，且**根本不调用业务层**（不白烧一次生成）")
    void anonymous_is_rejected_before_calling_service() throws Exception {
        mvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"q\"}"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(chat);
    }

    @Test
    @DisplayName("已认证 + 自带 X-Demo-Roles: hr ⇒ 仍然用令牌里的角色（防提权）")
    void demo_header_cannot_override_authenticated_roles() throws Exception {
        when(chat.ask(anyLong(), any(), any(), any(), any())).thenReturn(ok());

        mvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Demo-Roles", "hr")
                        .content("{\"question\":\"q\"}")
                        .requestAttr(JwtAuthFilter.ATTR_PRINCIPAL, principal(9L, "employee")))
                .andExpect(status().isOk());

        verify(chat).ask(eq(9L), eq(List.of("public")), eq("q"), isNull(), isNull());
    }

    @Test
    @DisplayName("配额用尽 ⇒ 429 + Retry-After（客户端能据此提示「明天再来」）")
    void quota_exceeded_is_429_with_retry_after() throws Exception {
        doThrow(new ChatService.QuotaExceeded(new QuotaService.Decision(false, 13, 12)))
                .when(chat).ask(anyLong(), any(), any(), any(), any());

        mvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"q\"}")
                        .requestAttr(JwtAuthFilter.ATTR_PRINCIPAL, principal(7L, "hr")))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.detail").value("今日配额已用尽（13/12）"));
    }

    @Test
    @DisplayName("空问题 ⇒ 400（先被校验挡住，不产生任何上游调用）")
    void blank_question_is_rejected() throws Exception {
        mvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"  \"}")
                        .requestAttr(JwtAuthFilter.ATTR_PRINCIPAL, principal(7L, "hr")))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(chat);
    }

    @Test
    @DisplayName("上游 4xx/5xx ⇒ 502（详情不回给浏览器）")
    void upstream_error_maps_to_502() throws Exception {
        doThrow(new PythonAiClient.PythonAiException(500, "Traceback ...", null))
                .when(chat).ask(anyLong(), any(), any(), any(), any());

        mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"q\"}")
                        .requestAttr(JwtAuthFilter.ATTR_PRINCIPAL, principal(7L, "hr")))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.detail").value("上游状态码 500，详情见服务端日志"));
    }

    @Test
    @DisplayName("连不上/超时 ⇒ 504（与 502 分开，处置动作不同）")
    void timeout_maps_to_504() throws Exception {
        doThrow(new org.springframework.web.client.ResourceAccessException("Read timed out"))
                .when(chat).ask(anyLong(), any(), any(), any(), any());

        mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"q\"}")
                        .requestAttr(JwtAuthFilter.ATTR_PRINCIPAL, principal(7L, "hr")))
                .andExpect(status().isGatewayTimeout());
    }

    @Test
    @DisplayName("联调头开关打开时，匿名请求才认它，且同样要经过投影")
    void demo_header_only_for_anonymous_when_enabled() {
        ChatService mockChat = org.mockito.Mockito.mock(ChatService.class);
        ChatController controller = new ChatController(mockChat, new AclProjector(), true);

        org.springframework.mock.web.MockHttpServletRequest req =
                new org.springframework.mock.web.MockHttpServletRequest();
        req.addHeader("X-Demo-Roles", "hr");
        controller.chat(new ChatController.AskBody("q", null, null), req);

        verify(mockChat).ask(eq(-1L), eq(List.of("hr", "public")), eq("q"), isNull(), isNull());
    }
}
