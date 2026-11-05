package com.policydesk.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 上下文能起来（配置绑定 + Bean 装配 + 数据源 + 种子数据）。
 *
 * <p>★ 用 {@code test} profile：数据源走 H2 内存库，种子账号由 {@code DemoUserSeeder} 插入。
 * 判据不能依赖"本机 MySQL 恰好开着、恰好有那个库、恰好有那个用户"——
 * 那种测试在别人机器上必红，而它红了以后没人会去修，只会被跳过。
 */
@SpringBootTest
@ActiveProfiles("test")
class PolicyDeskApplicationTests {

    @Test
    @DisplayName("Spring 上下文能加载（含 MyBatis-Plus、JWT、种子账号）")
    void contextLoads() {
        // 起不来就会在 @SpringBootTest 阶段炸；这里不需要额外断言。
    }
}
