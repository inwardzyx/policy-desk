package com.policydesk.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * 制度问答台的业务层入口。
 *
 * <p>这一层的职责（也是它与 Python 服务的分界）：
 * <ul>
 *   <li><b>有状态</b>：用户、角色、配额、审计、会话 —— 都在这一侧；</li>
 *   <li><b>无状态</b>：检索/精排/生成/评测 —— 在 Python 服务里，给它文本它给答案。</li>
 * </ul>
 * 判断某个逻辑该放哪边，只问一句：<b>它需不需要记住"上一次"</b>。
 * 需要 ⇒ 这里；不需要 ⇒ Python。
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class PolicyDeskApplication {

    public static void main(String[] args) {
        SpringApplication.run(PolicyDeskApplication.class, args);
    }
}
