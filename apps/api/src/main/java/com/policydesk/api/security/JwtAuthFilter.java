package com.policydesk.api.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 认证过滤器：把 {@code Authorization: Bearer <jwt>} 变成请求上的身份。
 *
 * <p>★ 三个刻意的设计：
 * <ol>
 *   <li><b>只在 {@code /api/**} 上生效</b>：{@code /actuator/health} 必须免认证，
 *       否则健康检查会变成"因为没人登录所以看起来挂了"（v1 也踩过同类：健康检查被限流挡住）。</li>
 *   <li><b>没有 token 不拦</b>：只放行到下一个环节，由需要身份的接口自己拒绝。
 *       这样 {@code /api/ping} 这类公开接口不需要在过滤器里维护白名单（白名单一定会漂）。</li>
 *   <li><b>有 token 但无效 ⇒ 立刻 401</b>：不能"当成匿名放过去"，
 *       那会让"令牌过期"表现为"权限不足"，排查方向完全不同。</li>
 * </ol>
 */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    /** 请求属性名：控制器里用它取身份，不额外包一层 ThreadLocal。 */
    public static final String ATTR_PRINCIPAL = "policy-desk.principal";

    private static final Logger log = LoggerFactory.getLogger(JwtAuthFilter.class);

    private final JwtService jwt;
    private final ObjectMapper json = new ObjectMapper();

    public JwtAuthFilter(JwtService jwt) {
        this.jwt = jwt;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            chain.doFilter(request, response);
            return;
        }
        String token = header.substring("Bearer ".length()).trim();
        try {
            JwtService.Principal p = jwt.parse(token);
            request.setAttribute(ATTR_PRINCIPAL, p);
            chain.doFilter(request, response);
        } catch (JwtService.InvalidTokenException e) {
            // ★ 原文只进日志：对客户端说"无效"就够，说清原因等于教人绕过。
            log.warn("拒绝请求 {}：{}", request.getRequestURI(), e.getMessage());
            ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.UNAUTHORIZED);
            pd.setTitle("未认证");
            pd.setDetail("令牌无效或已过期，请重新登录");
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            json.writeValue(response.getWriter(), pd);
        }
    }
}
