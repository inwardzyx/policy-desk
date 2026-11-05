package com.policydesk.api.web;

import com.policydesk.api.client.Dto;
import com.policydesk.api.client.PythonAiClient;
import com.policydesk.api.security.JwtAuthFilter;
import com.policydesk.api.security.JwtService;
import com.policydesk.api.service.AuthService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 业务层的判据：<b>角色只来自已认证的身份</b>，匿名一律 401。
 *
 * <p>★ 这一组里最重要的一条是「已认证时 X-Demo-Roles 不生效」：
 * 它是 M2 → M3 那次提权洞的回归用例。没有它，将来有人把联调头挪到前面，
 * 所有测试照样绿，而线上任何人都能自封 hr。
 */
@WebMvcTest(ChatController.class)
class ChatControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private PythonAiClient ai;

    @MockitoBean
    private JwtService jwt;

    private static JwtService.Principal hr() {
        return new JwtService.Principal(7L, "hr_demo", List.of("hr"));
    }

    @Test
    @DisplayName("已认证 ⇒ 角色来自令牌，转给 AI 服务")
    void forwards_roles_from_principal() throws Exception {
        when(ai.chat(any(), any(), any(), any()))
                .thenReturn(new Dto.ChatResponse("按 1.5 倍计算", List.of(), 12, true, true));

        mvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"加班费怎么算\"}")
                        .requestAttr(JwtAuthFilter.ATTR_PRINCIPAL, hr()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("按 1.5 倍计算"));

        verify(ai).chat(eq("加班费怎么算"), isNull(), eq(List.of("hr")), isNull());
    }

    @Test
    @DisplayName("匿名 ⇒ 401，且**根本不去调 AI**（不白烧一次生成）")
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

        JwtService.Principal employee = new JwtService.Principal(9L, "emp_demo", List.of("employee"));
        mvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Demo-Roles", "hr")
                        .content("{\"question\":\"q\"}")
                        .requestAttr(JwtAuthFilter.ATTR_PRINCIPAL, employee))
                .andExpect(status().isOk());

        verify(ai).chat(eq("q"), isNull(), eq(List.of("employee")), isNull());
    }

    @Test
    @DisplayName("空问题 ⇒ 400（先被校验挡住，不产生任何上游调用）")
    void blank_question_is_rejected() throws Exception {
        mvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"  \"}")
                        .requestAttr(JwtAuthFilter.ATTR_PRINCIPAL, hr()))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(ai);
    }

    @Test
    @DisplayName("上游 4xx/5xx ⇒ 502（详情不回给浏览器）")
    void upstream_error_maps_to_502() throws Exception {
        // ★ 用 doThrow 而不是 when(...).thenThrow(...)：后者会**先调用一次** mock，
        //   在"同一个方法要连续桩两种异常"的场景里，第二次桩会撞上第一次抛出的异常。
        org.mockito.Mockito.doThrow(new PythonAiClient.PythonAiException(500, "Traceback ...", null))
                .when(ai).chat(any(), any(), any(), any());

        mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"q\"}")
                        .requestAttr(JwtAuthFilter.ATTR_PRINCIPAL, hr()))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.detail").value("上游状态码 500，详情见服务端日志"));
    }

    @Test
    @DisplayName("连不上/超时 ⇒ 504（与 502 分开，处置动作不同）")
    void timeout_maps_to_504() throws Exception {
        org.mockito.Mockito.doThrow(new org.springframework.web.client.ResourceAccessException("Read timed out"))
                .when(ai).chat(any(), any(), any(), any());

        mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"q\"}")
                        .requestAttr(JwtAuthFilter.ATTR_PRINCIPAL, hr()))
                .andExpect(status().isGatewayTimeout());
    }

    @Test
    @DisplayName("联调头开关打开时，匿名请求才认它（默认关闭）")
    void demo_header_only_for_anonymous_when_enabled() {
        PythonAiClient mockAi = org.mockito.Mockito.mock(PythonAiClient.class);
        ChatController controller = new ChatController(mockAi, true);

        org.springframework.mock.web.MockHttpServletRequest req =
                new org.springframework.mock.web.MockHttpServletRequest();
        req.addHeader("X-Demo-Roles", "hr");
        controller.chat(new ChatController.AskBody("q", null, null), req);

        verify(mockAi).chat(eq("q"), isNull(), eq(List.of("hr")), isNull());
    }
}
