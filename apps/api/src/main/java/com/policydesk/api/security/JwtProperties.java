package com.policydesk.api.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * JWT 的签发参数。
 *
 * @param secret      HS256 的密钥。<b>至少 32 字节</b>（256 位），否则 HMAC-SHA256 的强度名不副实。
 * @param ttlMinutes  有效期（分钟）
 * @param issuer      签发者（写进 iss，便于以后多服务校验）
 */
@ConfigurationProperties(prefix = "policy-desk.jwt")
public record JwtProperties(String secret, int ttlMinutes, String issuer) {

    public JwtProperties {
        // ★ 与 INTERNAL_TOKEN 同一条纪律：密钥不对/太短，**启动就失败**。
        //   让它跑到运行时才炸，症状是"登录成功但所有接口 401"，最难查的一类。
        if (secret == null || secret.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException(
                    "policy-desk.jwt.secret 未配置或太短（HS256 要求 >= 32 字节）。"
                            + "开发环境可临时用一段 40 字符的随机串，生产请放密钥管理里");
        }
        if (ttlMinutes <= 0) {
            ttlMinutes = 120;
        }
        if (issuer == null) {
            issuer = "policy-desk";
        }
    }
}
