package com.policydesk.api.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 令牌判据：签得出、验得回、过期会拒、改一个字符就拒。
 *
 * <p>★ 用可控 {@link Clock} 而不是 {@code Thread.sleep}：
 * 睡 2 分钟来测"过期"会让整个测试套件多 2 分钟，而更常见的结局是没人跑它。
 */
class JwtServiceTest {

    private static final JwtProperties PROPS =
            new JwtProperties("0123456789abcdef0123456789abcdef", 2, "policy-desk");
    private static final Instant T0 = Instant.parse("2026-11-05T00:00:00Z");

    private JwtService at(Instant instant) {
        return new JwtService(PROPS, Clock.fixed(instant, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("签发 → 解析：身份与角色原样回来")
    void roundtrip() {
        JwtService svc = at(T0);
        JwtService.IssuedToken t = svc.issue(7L, "hr_demo", List.of("hr"));

        JwtService.Principal p = svc.parse(t.token());

        assertThat(p.userId()).isEqualTo(7L);
        assertThat(p.username()).isEqualTo("hr_demo");
        assertThat(p.roles()).containsExactly("hr");
        assertThat(t.expiresAt()).isEqualTo(T0.plusSeconds(2 * 60));
    }

    @Test
    @DisplayName("超过有效期 ⇒ 拒（不是「警告一下」）")
    void expired_is_rejected() {
        String token = at(T0).issue(1L, "u", List.of("public")).token();

        JwtService later = at(T0.plusSeconds(3 * 60));
        assertThatThrownBy(() -> later.parse(token))
                .isInstanceOf(JwtService.InvalidTokenException.class);
    }

    @Test
    @DisplayName("改一个字符 ⇒ 签名不匹配 ⇒ 拒")
    void tampered_is_rejected() {
        JwtService svc = at(T0);
        String token = svc.issue(1L, "u", List.of("public")).token();
        char last = token.charAt(token.length() - 1);
        String tampered = token.substring(0, token.length() - 1) + (last == 'A' ? 'B' : 'A');

        assertThatThrownBy(() -> svc.parse(tampered))
                .isInstanceOf(JwtService.InvalidTokenException.class);
    }

    @Test
    @DisplayName("换一个签名密钥 ⇒ 拒（不同部署之间不能互认令牌）")
    void other_secret_is_rejected() {
        String token = at(T0).issue(1L, "u", List.of("public")).token();

        JwtProperties other = new JwtProperties("ffffffffffffffffffffffffffffffff", 2, "policy-desk");
        JwtService otherSvc = new JwtService(other, Clock.fixed(T0, ZoneOffset.UTC));

        assertThatThrownBy(() -> otherSvc.parse(token))
                .isInstanceOf(JwtService.InvalidTokenException.class);
    }

    @Test
    @DisplayName("密钥短于 32 字节 ⇒ 构造即失败（HS256 的强度不能被配置糊弄）")
    void short_secret_is_rejected() {
        assertThatThrownBy(() -> new JwtProperties("too-short", 2, "policy-desk"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32");
    }
}
