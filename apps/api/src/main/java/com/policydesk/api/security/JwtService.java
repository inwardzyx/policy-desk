package com.policydesk.api.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;

/**
 * 签发与校验 JWT（HS256）。
 *
 * <p>★ 为什么不用 Spring Security 的全套过滤器链：
 * 这个系统只有一种身份（登录用户 + 角色），而引入 Security 的默认链条会让
 * "哪个请求被谁拦了"变得难查（一条 401 可能来自七八个地方）。
 * 现在只有**一个**过滤器、**一处**判据，出问题的可能性比"配对了但配错了"小得多。
 * 等真的需要 OAuth2/方法级权限时再引，那时是加需求，不是加复杂度。
 */
@Service
public class JwtService {

    /** 令牌里带的最小信息：谁、什么角色。 */
    public record Principal(long userId, String username, List<String> roles) {
    }

    public record IssuedToken(String token, Instant expiresAt) {
    }

    private final SecretKey key;
    private final JwtProperties props;
    private final Clock clock;

    // ★ 必须显式 @Autowired：这个类有两个构造器（一个给生产、一个给测试注入时钟），
    //   不给标记的话 Spring 会去找无参构造器，然后报 "No default constructor found"。
    @org.springframework.beans.factory.annotation.Autowired
    public JwtService(JwtProperties props) {
        this(props, Clock.systemDefaultZone());
    }

    /** 测试用：注入可控时钟，才能确定性地测"过期"。 */
    public JwtService(JwtProperties props, Clock clock) {
        this.props = props;
        this.clock = clock;
        this.key = Keys.hmacShaKeyFor(props.secret().getBytes(StandardCharsets.UTF_8));
    }

    public IssuedToken issue(long userId, String username, List<String> roles) {
        Instant now = clock.instant();
        Instant exp = now.plus(props.ttlMinutes(), ChronoUnit.MINUTES);
        String token = Jwts.builder()
                .issuer(props.issuer())
                .subject(username)
                .claim("uid", userId)
                .claim("roles", roles)
                .issuedAt(Date.from(now))
                .expiration(Date.from(exp))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
        return new IssuedToken(token, exp);
    }

    /**
     * 校验并取出身份。
     *
     * @throws InvalidTokenException 过期、签名不对、issuer 不对、格式坏 —— 一律 401，不区分原因
     *                               （对客户端说清"为什么"等于告诉攻击者怎么绕过）
     */
    public Principal parse(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .requireIssuer(props.issuer())
                    .clock(() -> Date.from(clock.instant()))
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            Object uid = claims.get("uid");
            List<?> roles = claims.get("roles", List.class);
            return new Principal(
                    uid == null ? -1L : ((Number) uid).longValue(),
                    claims.getSubject(),
                    roles == null ? List.of() : roles.stream().map(String::valueOf).toList());
        } catch (JwtException | IllegalArgumentException e) {
            throw new InvalidTokenException("令牌无效或已过期", e);
        }
    }

    /** 令牌不合法。故意不带细节：对外只说"无效"。 */
    public static class InvalidTokenException extends RuntimeException {
        public InvalidTokenException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
