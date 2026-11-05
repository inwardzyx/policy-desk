package com.policydesk.api.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 过滤器的三条行为。
 *
 * <p>★ 第 2 条是这套设计里最容易写错、也最难发现的一条：
 * <b>令牌无效时必须 401，而不是"当成匿名放过"</b>。
 * 放过的后果是"令牌过期"在业务层表现为"权限不足"，排查方向完全错。
 */
class JwtAuthFilterTest {

    private static final JwtProperties PROPS =
            new JwtProperties("0123456789abcdef0123456789abcdef", 60, "policy-desk");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-05T00:00:00Z"), ZoneOffset.UTC);

    private final JwtService jwt = new JwtService(PROPS, CLOCK);
    private final JwtAuthFilter filter = new JwtAuthFilter(jwt);

    @Test
    @DisplayName("没有 Authorization 头 ⇒ 放行但不设身份（要不要拒绝由接口自己决定）")
    void no_header_passes_through() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/chat");
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(req, res, chain);

        assertThat(req.getAttribute(JwtAuthFilter.ATTR_PRINCIPAL)).isNull();
        assertThat(chain.getRequest()).isNotNull();          // 真的继续走下去了
        assertThat(res.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("令牌无效 ⇒ 401，且**不再往下走**")
    void invalid_token_stops_the_chain() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/chat");
        req.addHeader("Authorization", "Bearer not-a-jwt");
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(401);
        assertThat(res.getContentAsString()).contains("令牌无效或已过期");
        assertThat(chain.getRequest()).isNull();             // 没有继续
        assertThat(req.getAttribute(JwtAuthFilter.ATTR_PRINCIPAL)).isNull();
    }

    @Test
    @DisplayName("令牌有效 ⇒ 身份写进请求属性，继续往下走")
    void valid_token_sets_principal() throws Exception {
        String token = jwt.issue(7L, "hr_demo", List.of("hr")).token();
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/chat");
        req.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(req, res, chain);

        Object p = req.getAttribute(JwtAuthFilter.ATTR_PRINCIPAL);
        assertThat(p).isInstanceOf(JwtService.Principal.class);
        assertThat(((JwtService.Principal) p).roles()).containsExactly("hr");
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    @DisplayName("/actuator/** 不经过这个过滤器（否则健康检查会因为没登录而「看起来挂了」）")
    void non_api_paths_are_skipped() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/actuator/health");
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(req, res, chain);

        // shouldNotFilter 生效：即使是坏令牌也不会被拦
        assertThat(res.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isNotNull();
    }
}
