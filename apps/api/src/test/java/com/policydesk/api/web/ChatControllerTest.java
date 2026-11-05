package com.policydesk.api.web;

import com.policydesk.api.client.Dto;
import com.policydesk.api.client.PythonAiClient;
import com.policydesk.api.security.AclProjector;
import com.policydesk.api.security.JwtAuthFilter;
import com.policydesk.api.security.JwtService;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 业务层的判据：<b>角色只来自令牌，且必须经过投影才能变成 ACL</b>。
 *
 * <p>★ 这一组里最值钱的两条：
 * <ul>
 *   <li>「已认证时 X-Demo-Roles 不生效」—— M2 → M3 那次提权洞的回归；</li>
 *   <li>「发给 AI 的是 ACL 而不是原始角色」—— 如果哪天有人图省事把 roles 直接透传，
 *       头会变成 {@code hr}（而不是 {@code hr,public}），employee 那条也会变成
 *       {@code public} 恰好对得上 —— 只有断言 hr 那条能抓住。</li>
 * </ul>
 */
@WebMvcTest(ChatController.class)
@Import(AclProjector.class)
class ChatControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private PythonAiClient ai;

    @MockitoBean
    private JwtService jwt;

    private static JwtService.Principal principal(String... roles) {
        return new JwtService.Principal(7L, "someone", List.of(roles));
    }

    @Test
    @DisplayName("hr 角色 ⇒ 发给 AI 的是 ACL「hr,public」（不是角色名 hr）")
    void hr_role_is_projected_to_acl() throws Exception {
        when(ai.chat(any(), any(), any(), any()))
                .thenReturn(new Dto.ChatResponse("按 1.5 倍计算", List.of(), 12, true, true));

        mvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"加班费怎么算\"}")
                        .requestAttr(JwtAuthFilter.ATTR_PRINCIPAL, principal("hr")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("按 1.5 倍计算"));

        verify(ai).chat(eq("加班费怎么算"), isNull(), eq(List.of("hr", "public")), isNull());
    }

    @Test
    @DisplayName("employee 角色 ⇒ 只有 public（拿不到 hr 条款）")
    void employee_role_gets_public_only() throws Exception {
        when(ai.chat(any(), any(), any(), any()))
                .thenReturn(new Dto.ChatResponse("ok", List.of(), 1, false, false));

        mvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"q\"}")
                        .requestAttr(JwtAuthFilter.ATTR_PRINCIPAL, principal("employee")))
                .andExpect(status().isOk());

        verify(ai).chat(eq("q"), isNull(), eq(List.of("public")), isNull());
    }

    @Test
    @DisplayName("角色没登记 ⇒ 403，且**不调用 AI**")
    void unknown_role_is_403_without_calling_ai() throws Exception {
        mvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"q\"}")
                        .requestAttr(JwtAuthFilter.ATTR_PRINCIPAL, principal("HRR")))
                .andExpect(status().isForbidden());

        verifyNoInteractions(ai);
    }

    @Test
    @DisplayName("匿名 ⇒ 401，且**根本不调用 AI**（不白烧一次生成）")
    void anonymous_is_rejected_before_calling_ai() throws Exception {
        mvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"q\"}"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(ai);
    }

    @Test
    @DisplayName("已认证 + 自带 X-Demo-Roles: hr ⇒ 仍然用令牌里的角色（防提权）")
    void demo_header_cannot_override_authenticated_roles() throws Exception {
        when(ai.chat(any(), any(), any(), any()))
                .thenReturn(new Dto.ChatResponse("ok", List.of(), 1, false, false));

        mvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Demo-Roles", "hr")
                        .content("{\"question\":\"q\"}")
                        .requestAttr(JwtAuthFilter.ATTR_PRINCIPAL, principal("employee")))
                .andExpect(status().isOk());

        verify(ai).chat(eq("q"), isNull(), eq(List.of("public")), isNull());
    }

    @Test
    @DisplayName("空问题 ⇒ 400（先被校验挡住，不产生任何上游调用）")
    void blank_question_is_rejected() throws Exception {
        mvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"  \"}")
                        .requestAttr(JwtAuthFilter.ATTR_PRINCIPAL, principal("hr")))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(ai);
    }

    @Test
    @DisplayName("上游 4xx/5xx ⇒ 502（详情不回给浏览器）")
    void upstream_error_maps_to_502() throws Exception {
        // ★ 用 doThrow 而不是 when(...).thenThrow(...)：后者会**先调用一次** mock，
        //   在"同一个方法要连续桩两种异常"的场景里，第二次桩会撞上第一次抛出的异常。
        doThrow(new PythonAiClient.PythonAiException(500, "Traceback ...", null))
                .when(ai).chat(any(), any(), any(), any());

        mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"q\"}")
                        .requestAttr(JwtAuthFilter.ATTR_PRINCIPAL, principal("hr")))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.detail").value("上游状态码 500，详情见服务端日志"));
    }

    @Test
    @DisplayName("连不上/超时 ⇒ 504（与 502 分开，处置动作不同）")
    void timeout_maps_to_504() throws Exception {
        doThrow(new org.springframework.web.client.ResourceAccessException("Read timed out"))
                .when(ai).chat(any(), any(), any(), any());

        mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"q\"}")
                        .requestAttr(JwtAuthFilter.ATTR_PRINCIPAL, principal("hr")))
                .andExpect(status().isGatewayTimeout());
    }

    @Test
    @DisplayName("联调头开关打开时，匿名请求才认它，且同样要经过投影")
    void demo_header_only_for_anonymous_when_enabled() {
        PythonAiClient mockAi = org.mockito.Mockito.mock(PythonAiClient.class);
        ChatController controller = new ChatController(mockAi, new AclProjector(), true);

        org.springframework.mock.web.MockHttpServletRequest req =
                new org.springframework.mock.web.MockHttpServletRequest();
        req.addHeader("X-Demo-Roles", "hr");
        controller.chat(new ChatController.AskBody("q", null, null), req);

        verify(mockAi).chat(eq("q"), isNull(), eq(List.of("hr", "public")), isNull());
    }
}
