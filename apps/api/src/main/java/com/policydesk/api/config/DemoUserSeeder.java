package com.policydesk.api.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.policydesk.api.entity.User;
import com.policydesk.api.mapper.UserMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 开发用种子账号（默认<b>关</b>）。
 *
 * <p>★ 为什么不把 BCrypt 哈希写死进 {@code init.sql}：
 * 写死的那一刻起，"演示口令"就成了一条永久后门 —— 谁都能拿它登录你的线上库。
 * 这里改成运行时用 {@link PasswordEncoder} 现算，并且**只在显式开启且用户表为空时**插入。
 *
 * <p>★ 为什么还要判断"表为空"：否则每次启动都会尝试插一遍，
 * 第二次起就是唯一键冲突 → 启动失败。种子数据必须是幂等的。
 */
@Component
public class DemoUserSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoUserSeeder.class);

    private final UserMapper users;
    private final PasswordEncoder encoder;
    private final boolean enabled;
    private final String demoPassword;

    public DemoUserSeeder(UserMapper users,
                          PasswordEncoder encoder,
                          @Value("${policy-desk.seed-demo-users:false}") boolean enabled,
                          @Value("${policy-desk.demo-password:}") String demoPassword) {
        this.users = users;
        this.encoder = encoder;
        this.enabled = enabled;
        this.demoPassword = demoPassword;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            return;
        }
        if (demoPassword == null || demoPassword.isBlank()) {
            // 不静默跳过：开了种子却没给口令，是最容易"以为有账号其实没有"的状态。
            throw new IllegalStateException(
                    "policy-desk.seed-demo-users=true 但没有给 policy-desk.demo-password");
        }
        Long n = users.selectCount(new LambdaQueryWrapper<>());
        if (n != null && n > 0) {
            log.info("用户表已有 {} 条记录，跳过种子账号", n);
            return;
        }
        insert("hr_demo", "hr");
        insert("emp_demo", "employee");
        log.warn("已插入演示账号 hr_demo / emp_demo（仅开发环境；生产必须关掉 seed-demo-users）");
    }

    private void insert(String username, String role) {
        User u = new User();
        u.setUsername(username);
        u.setPasswordHash(encoder.encode(demoPassword));
        u.setRole(role);
        u.setEnabled(true);
        users.insert(u);
    }
}
