package com.policydesk.api.service;

import com.policydesk.api.entity.User;
import com.policydesk.api.mapper.UserMapper;
import com.policydesk.api.security.JwtProperties;
import com.policydesk.api.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 登录判据：对的口令给令牌，错的/不存在的/被禁用的**一律同一个错误**。
 */
class AuthServiceTest {

    private static final String RAW = "policy123";

    private UserMapper mapper;
    private AuthService auth;
    private final PasswordEncoder encoder = new BCryptPasswordEncoder(4); // 测试里用低强度，快

    @BeforeEach
    void setUp() {
        mapper = mock(UserMapper.class);
        JwtProperties props = new JwtProperties("0123456789abcdef0123456789abcdef", 60, "policy-desk");
        JwtService jwt = new JwtService(props, Clock.fixed(Instant.parse("2026-11-05T00:00:00Z"),
                ZoneOffset.UTC));
        auth = new AuthService(mapper, encoder, jwt);
    }

    private User user(String role, boolean enabled) {
        User u = new User();
        u.setId(7L);
        u.setUsername("hr_demo");
        u.setPasswordHash(encoder.encode(RAW));
        u.setRole(role);
        u.setEnabled(enabled);
        return u;
    }

    @Test
    @DisplayName("口令正确 ⇒ 令牌 + 角色（角色来自库，不来自请求）")
    void login_ok() {
        when(mapper.selectOne(any())).thenReturn(user("hr", true));

        AuthService.LoginResult r = auth.login("hr_demo", RAW);

        assertThat(r.token()).isNotBlank();
        assertThat(r.roles()).containsExactly("hr");
        assertThat(r.username()).isEqualTo("hr_demo");
    }

    @Test
    @DisplayName("口令错 ⇒ BadCredentials")
    void wrong_password_rejected() {
        when(mapper.selectOne(any())).thenReturn(user("hr", true));

        assertThatThrownBy(() -> auth.login("hr_demo", "wrong"))
                .isInstanceOf(AuthService.BadCredentials.class);
    }

    @Test
    @DisplayName("用户不存在 ⇒ 同一个 BadCredentials（不给枚举器留口子）")
    void unknown_user_rejected_identically() {
        when(mapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> auth.login("ghost", RAW))
                .isInstanceOf(AuthService.BadCredentials.class)
                .hasMessage("用户名或口令错误");
    }

    @Test
    @DisplayName("账号被禁用 ⇒ 同样拒绝（且不泄漏「账号存在但禁用」）")
    void disabled_user_rejected() {
        when(mapper.selectOne(any())).thenReturn(user("hr", false));

        assertThatThrownBy(() -> auth.login("hr_demo", RAW))
                .isInstanceOf(AuthService.BadCredentials.class);
    }
}
