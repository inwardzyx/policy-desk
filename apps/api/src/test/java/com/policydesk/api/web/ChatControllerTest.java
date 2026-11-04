package com.policydesk.api.web;

import com.policydesk.api.client.Dto;
import com.policydesk.api.client.PythonAiClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.ResourceAccessException;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 业务层的三条判据：角色从哪来、上游出错翻成什么码。
 *
 * <p>★ 第一条是这一层存在的理由：<b>角色必须由服务端决定</b>。
 * 这里断言"头里的角色被原样接受、没头时退到 public"，
 * 到 M3 接上登录后，同一个断言会改成"从 JWT 里取" —— 位置不变，来源换掉。
 */
@WebMvcTest(ChatController.class)
class ChatControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private PythonAiClient ai;

    @Test
    @DisplayName("X-Demo-Roles: hr,public ⇒ 原样转给 AI 服务")
    void forwards_demo_roles() throws Exception {
        when(ai.chat(any(), any(), any(), any()))
                .thenReturn(new Dto.ChatResponse("按 1.5 倍计算", List.of(), 12, true, true));

        mvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Demo-Roles", "hr,public")
                        .content("{\"question\":\"加班费怎么算\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("按 1.5 倍计算"));

        verify(ai).chat(eq("加班费怎么算"), isNull(), eq(List.of("hr", "public")), isNull());
    }

    @Test
    @DisplayName("没有角色头 ⇒ 只给 public（不猜、不继承）")
    void defaults_to_public() throws Exception {
        when(ai.chat(any(), any(), any(), any()))
                .thenReturn(new Dto.ChatResponse("ok", List.of(), 1, false, false));

        mvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"q\"}"))
                .andExpect(status().isOk());

        verify(ai).chat(eq("q"), isNull(), eq(List.of("public")), isNull());
    }

    @Test
    @DisplayName("上游 4xx/5xx ⇒ 502（不是 500，也不是把上游 body 透给浏览器）")
    void upstream_error_maps_to_502() throws Exception {
        when(ai.chat(any(), any(), any(), any()))
                .thenThrow(new PythonAiClient.PythonAiException(500, "Traceback ...", null));

        mvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"q\"}"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.detail").value("上游状态码 500，详情见服务端日志"));
    }

    @Test
    @DisplayName("连不上/超时 ⇒ 504（与 502 分开，处置动作不同）")
    void timeout_maps_to_504() throws Exception {
        when(ai.chat(any(), any(), any(), any()))
                .thenThrow(new ResourceAccessException("Read timed out"));

        mvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"q\"}"))
                .andExpect(status().isGatewayTimeout());
    }

    @Test
    @DisplayName("开关关掉时，即便客户端传了角色头也只给 public（防提权）")
    void demo_header_can_be_disabled() {
        PythonAiClient mockAi = Mockito.mock(PythonAiClient.class);
        ChatController controller = new ChatController(mockAi, false);

        controller.chat(new ChatController.AskBody("q", null, null), "hr");

        verify(mockAi).chat(eq("q"), isNull(), eq(List.of("public")), isNull());
    }

    @Test
    @DisplayName("空问题被 Bean Validation 挡住（400，不会白烧一次生成）")
    void blank_question_is_rejected() throws Exception {
        mvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"  \"}"))
                .andExpect(status().isBadRequest());
    }
}
