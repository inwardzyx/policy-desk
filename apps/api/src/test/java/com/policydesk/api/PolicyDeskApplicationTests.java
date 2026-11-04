package com.policydesk.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 上下文能起来（配置绑定 + Bean 装配）。
 *
 * <p>★ 这里的 base-url 故意指向一个**不通**的端口（9）：
 * 启动不该依赖上游可达 —— 否则"Python 还没起来"就会让 Java 也起不来，
 * 部署顺序被绑死。上游可用性由 {@code /actuator/health} 的 pythonAi 组件报。
 */
@SpringBootTest(properties = {
        "python-ai.internal-token=test-token",
        "python-ai.base-url=http://127.0.0.1:9",
})
class PolicyDeskApplicationTests {

    @Test
    @DisplayName("Spring 上下文能加载（配置绑定、Bean 装配都没有循环依赖）")
    void contextLoads() {
        // 什么都不用断言：起不来就会在 @SpringBootTest 阶段炸。
    }
}
