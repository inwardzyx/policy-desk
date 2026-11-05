package com.policydesk.api.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.policydesk.api.entity.User;
import com.policydesk.api.mapper.UserMapper;
import com.policydesk.api.security.JwtService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * 登录：核对口令 → 签发令牌。
 *
 * <p>★ 两条安全纪律（都写在这里，因为这里才是唯一该管它们的地方）：
 * <ol>
 *   <li><b>用户不存在与口令错误返回同一个错误</b>：否则 401 与"用户不存在"的区别
 *       就是一份免费的账号枚举接口。</li>
 *   <li><b>用户不存在时也做一次哈希比对</b>：BCrypt 是故意慢的（几十毫秒），
 *       跳过它会让"账号存在"与"不存在"的响应时间差出 10 倍 —— 时序侧信道。</li>
 * </ol>
 */
@Service
public class AuthService {

    /** 不存在的用户也拿它比一次，让两条路径的耗时接近。 */
    private static final String DUMMY_HASH =
            "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

    private final UserMapper users;
    private final PasswordEncoder encoder;
    private final JwtService jwt;

    public AuthService(UserMapper users, PasswordEncoder encoder, JwtService jwt) {
        this.users = users;
        this.encoder = encoder;
        this.jwt = jwt;
    }

    public record LoginResult(String token, Instant expiresAt, String username, List<String> roles) {
    }

    public LoginResult login(String username, String rawPassword) {
        User u = users.selectOne(new LambdaQueryWrapper<User>().eq(User::getUsername, username));
        if (u == null) {
            encoder.matches(rawPassword, DUMMY_HASH);      // 时序对齐，结果丢弃
            throw new BadCredentials();
        }
        if (!Boolean.TRUE.equals(u.getEnabled())) {
            encoder.matches(rawPassword, DUMMY_HASH);
            throw new BadCredentials();
        }
        if (!encoder.matches(rawPassword, u.getPasswordHash())) {
            throw new BadCredentials();
        }
        List<String> roles = List.of(u.getRole());
        JwtService.IssuedToken t = jwt.issue(u.getId(), u.getUsername(), roles);
        return new LoginResult(t.token(), t.expiresAt(), u.getUsername(), roles);
    }

    /** 登录失败。对外一律 401，不区分"没这个人"与"口令错"。 */
    public static class BadCredentials extends RuntimeException {
        public BadCredentials() {
            super("用户名或口令错误");
        }
    }
}
