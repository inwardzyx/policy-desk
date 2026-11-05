package com.policydesk.api.web;

import com.policydesk.api.service.AuthService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 登录接口的对外契约：成功给令牌，失败**只有一种** 401。
 *
 * <p>★ 为什么要单独测"两种失败长得一样"：账号不存在返回"无此用户"、口令错返回"口令错"，
 * 是一个免费的用户名枚举器。这条判据就是钉住它们不能分家。
 */
@WebMvcTest(AuthController.class)
class AuthControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private AuthService auth;

    /** 认证过滤器是 @Component，@WebMvcTest 会把 Filter 一起装配进来，所以它依赖的 JwtService 也要给桩。 */
    @MockitoBean
    private com.policydesk.api.security.JwtService jwtService;

    @Test
    @DisplayName("成功 ⇒ 200 + token + roles")
    void login_ok() throws Exception {
        when(auth.login(any(), any())).thenReturn(
                new AuthService.LoginResult("jwt-token", Instant.parse("2026-11-05T10:00:00Z"),
                        "hr_demo", List.of("hr")));

        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"hr_demo\",\"password\":\"policy123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value("jwt-token"))
                .andExpect(jsonPath("$.username").value("hr_demo"))
                .andExpect(jsonPath("$.roles[0]").value("hr"));
    }

    @Test
    @DisplayName("用户不存在与口令错误 ⇒ 同一个 401（不给枚举器留口子）")
    void login_failure_is_indistinguishable() throws Exception {
        when(auth.login(any(), any())).thenThrow(new AuthService.BadCredentials());

        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"no_such_user\",\"password\":\"x\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("用户名或口令错误"));
    }

    @Test
    @DisplayName("空用户名/口令 ⇒ 400（不查库、不比哈希）")
    void blank_input_is_400() throws Exception {
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"\",\"password\":\"\"}"))
                .andExpect(status().isBadRequest());
    }
}
