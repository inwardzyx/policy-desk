package com.policydesk.api.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 口令哈希器。
 *
 * <p>★ 为什么是 BCrypt 而不是 MD5/SHA256：
 * 后两者是"快"的哈希，专门用于摘要；口令哈希要的是**故意慢**（每次几十毫秒），
 * 让离线爆破的成本从秒级变成年级。BCrypt 自带 salt，不需要额外存盐。
 *
 * <p>★ 强度 10 是默认值：再高会拖慢登录（每 +1 约翻倍），在实习项目的量级上没收益。
 */
@Configuration
public class SecurityBeans {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(10);
    }
}
