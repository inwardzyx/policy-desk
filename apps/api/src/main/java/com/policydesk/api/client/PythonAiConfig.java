package com.policydesk.api.client;

import com.policydesk.api.config.PythonAiProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * 组装访问 Python 服务的 {@link RestClient}。
 *
 * <p>★ 为什么用 {@code RestClient} 而不是 {@code RestTemplate} 或 Feign：
 * <ul>
 *   <li>{@code RestTemplate} 在 Spring 6.1 起进入维护态，新代码不该再起用它；</li>
 *   <li>Feign 要多一套注册中心/负载均衡的心智负担，这里只有一个上游，用不上；</li>
 *   <li>{@code RestClient} 是同一个 HTTP 客户端之上的流式门面，且**可以绑 MockRestServiceServer**
 *       做确定性测试（见 {@code PythonAiClientTest}）—— 这是"判据能反向验证"的前提。</li>
 * </ul>
 *
 * <p>★ 超时必须显式设置：默认值在连接挂住时会一直等，表现为"页面白屏但日志没错误"，
 * 最难排查的那一类。
 */
@Configuration
public class PythonAiConfig {

    @Bean
    public RestClient pythonAiRestClient(PythonAiProperties props) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(props.connectTimeoutMs()));
        factory.setReadTimeout(Duration.ofMillis(props.readTimeoutMs()));
        return RestClient.builder()
                .baseUrl(props.baseUrl())
                .requestFactory(factory)
                .build();
    }
}
