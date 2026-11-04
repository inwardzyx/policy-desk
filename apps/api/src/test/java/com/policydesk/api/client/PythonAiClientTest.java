package com.policydesk.api.client;

import com.policydesk.api.config.PythonAiProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 契约判据：Java → Python 这一跳，**发出去的到底是什么**。
 *
 * <p>★ 为什么不用真 Python 服务测：
 * 真服务要加载 embedding 模型、要烧 token，跑一次几十秒且结果不确定。
 * 而这里要证明的东西是**确定性的**（头的名字、头的值、body 的形状），
 * 用 MockRestServiceServer 桩掉更准 —— 它连"头写错了"都能抓到，
 * 而真服务测试只会给你一个 401，让你去猜是哪个头不对。
 */
class PythonAiClientTest {

    private static final String TOKEN = "t0ken";

    private RestClient.Builder builder;
    private MockRestServiceServer server;
    private PythonAiClient client;

    private void setUp() {
        builder = RestClient.builder().baseUrl("http://ai.test");
        server = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();
        PythonAiProperties props = new PythonAiProperties("http://ai.test", TOKEN, 2000, 60000);
        client = new PythonAiClient(restClient, props);
    }

    @Test
    @DisplayName("请求带对了两个头，且角色用逗号拼接")
    void sends_internal_token_and_roles() {
        setUp();
        server.expect(requestTo("http://ai.test/internal/chat"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Internal-Token", TOKEN))
                .andExpect(header("X-Auth-Roles", "hr,public"))
                .andExpect(content().json("""
                        {"question":"加班费怎么算","namespace":"company","sessionId":null}
                        """, true))
                .andRespond(withSuccess("""
                        {"answer":"按 1.5 倍","sources":[],"took_ms":12,
                         "rerank_used":true,"knowledge_hit":true,"usage":{"n_calls":2}}
                        """, MediaType.APPLICATION_JSON));

        Dto.ChatResponse r = client.chat("加班费怎么算", "company", List.of("hr", "public"), null);

        assertThat(r.answer()).isEqualTo("按 1.5 倍");
        assertThat(r.took_ms()).isEqualTo(12);
        server.verify();
    }

    @Test
    @DisplayName("上游 401 必须变成可判断的异常（带原始状态码），不能吞成 null")
    void upstream_error_becomes_exception() {
        setUp();
        server.expect(requestTo("http://ai.test/internal/chat"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED).body("缺少或错误的 X-Internal-Token"));

        assertThatThrownBy(() -> client.chat("q", null, List.of("public"), null))
                .isInstanceOf(PythonAiClient.PythonAiException.class)
                .satisfies(e -> assertThat(((PythonAiClient.PythonAiException) e).upstreamStatus())
                        .isEqualTo(401));
    }

    @Test
    @DisplayName("上游多返回字段不会让解析炸（Python 侧加过 session_used / context / citation）")
    void tolerates_unknown_fields() {
        setUp();
        server.expect(requestTo("http://ai.test/internal/chat"))
                .andRespond(withSuccess("""
                        {"answer":"ok","sources":[],"took_ms":1,"rerank_used":false,
                         "knowledge_hit":false,"session_used":true,"context":{"trace_id":"abc"}}
                        """, MediaType.APPLICATION_JSON));

        Dto.ChatResponse r = client.chat("q", null, List.of("public"), null);
        assertThat(r.answer()).isEqualTo("ok");
        assertThat(r.knowledge_hit()).isFalse();
    }
}
