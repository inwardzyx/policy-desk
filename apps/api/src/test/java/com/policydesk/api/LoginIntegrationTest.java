package com.policydesk.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 端到端（H2 内存库，不碰本机 MySQL）：种子账号 → 登录 → 用令牌问"我是谁"。
 *
 * <p>★ 这条覆盖的是**装配**：种子数据真的插进去了、BCrypt 真的对得上、
 * 过滤器真的把身份写进去了、{@code /api/me} 真的读到了。
 * 每一项单测都覆盖不到"它们连起来"这件事。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LoginIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Test
    @DisplayName("hr_demo 登录 → 拿令牌 → /api/me 返回 hr（全链路）")
    void login_then_me() throws Exception {
        MvcResult r = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"hr_demo\",\"password\":\"policy123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles[0]").value("hr"))
                .andReturn();

        String body = r.getResponse().getContentAsString();
        String token = body.replaceAll(".*\"token\":\"([^\"]+)\".*", "$1");
        assertThat(token).isNotBlank().isNotEqualTo(body);

        mvc.perform(get("/api/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("hr_demo"))
                .andExpect(jsonPath("$.roles[0]").value("hr"));
    }

    @Test
    @DisplayName("不带令牌访问 /api/me ⇒ 401")
    void me_without_token_is_401() throws Exception {
        mvc.perform(get("/api/me")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("坏令牌访问 /api/me ⇒ 401（过滤器拦，不是「匿名放行」）")
    void me_with_bad_token_is_401() throws Exception {
        mvc.perform(get("/api/me").header("Authorization", "Bearer garbage"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("健康检查免登录，且**上游不可用时会真的报红**（db=UP / pythonAi=DOWN / 整体 503）")
    void health_surfaces_dependency_failure() throws Exception {
        // 测试 profile 把 python-ai 指向一个不通的端口（9）——这正是要证明的事：
        // 健康检查不能只说"我这个进程活着"，否则"Java 活着但 AI 挂了"时监控一片绿。
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("DOWN"))
                .andExpect(jsonPath("$.components.db.status").value("UP"))
                .andExpect(jsonPath("$.components.pythonAi.status").value("DOWN"));
    }
}
