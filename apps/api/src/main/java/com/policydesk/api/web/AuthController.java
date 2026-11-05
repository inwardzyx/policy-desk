package com.policydesk.api.web;

import com.policydesk.api.security.JwtAuthFilter;
import com.policydesk.api.security.JwtService;
import com.policydesk.api.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * 登录与"我是谁"。
 *
 * <p>★ 为什么单独开一个 {@code /api/me}：它是**过滤器是否真的生效**的最小判据。
 * 没有它，你只能通过"业务接口 401 还是有数据"间接推断令牌对不对，
 * 而有了它，一个 curl 就能看见"服务端认为我是谁、什么角色"。
 */
@RestController
@RequestMapping("/api")
public class AuthController {

    private final AuthService auth;

    public AuthController(AuthService auth) {
        this.auth = auth;
    }

    public record LoginBody(@NotBlank String username, @NotBlank String password) {
    }

    public record LoginResponse(String token, String expiresAt, String username, List<String> roles) {
    }

    public record MeResponse(long userId, String username, List<String> roles) {
    }

    @PostMapping("/auth/login")
    @ResponseStatus(HttpStatus.OK)
    public LoginResponse login(@RequestBody @Valid LoginBody body) {
        AuthService.LoginResult r = auth.login(body.username(), body.password());
        return new LoginResponse(r.token(), r.expiresAt().toString(), r.username(), r.roles());
    }

    @GetMapping("/me")
    public MeResponse me(HttpServletRequest request) {
        JwtService.Principal p = (JwtService.Principal) request.getAttribute(JwtAuthFilter.ATTR_PRINCIPAL);
        if (p == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未认证：缺少或无效的 Bearer 令牌");
        }
        return new MeResponse(p.userId(), p.username(), p.roles());
    }
}
