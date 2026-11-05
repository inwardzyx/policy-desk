package com.policydesk.api.web;

import com.policydesk.api.client.PythonAiClient;
import com.policydesk.api.security.AclProjector;
import com.policydesk.api.service.AuthService;
import com.policydesk.api.service.ChatService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 把各种失败翻译成**可判断**的状态码。
 *
 * <p>一张表：
 * <pre>
 *   401  未认证 / 令牌无效 / 登录失败
 *   403  角色没登记 ACL 映射（身份为真、权限未定义）
 *   429  今日配额用尽（带 Retry-After，指向次日 0 点）
 *   502  AI 服务答了个错（4xx/5xx）
 *   504  等不到 AI 服务（连接/读超时）
 * </pre>
 * 合并成 500 就等于让人从日志里猜。
 *
 * <p>★ 上游原文只进日志，不进响应体：里面可能有 Python 的栈信息与内网地址。
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    @ExceptionHandler(PythonAiClient.PythonAiException.class)
    public ProblemDetail onUpstreamError(PythonAiClient.PythonAiException e) {
        log.warn("AI 服务返回错误 status={} body={}", e.upstreamStatus(), e.getMessage());
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.BAD_GATEWAY);
        pd.setTitle("AI 服务返回错误");
        pd.setDetail("上游状态码 " + e.upstreamStatus() + "，详情见服务端日志");
        return pd;
    }

    @ExceptionHandler(ResourceAccessException.class)
    public ProblemDetail onTimeout(ResourceAccessException e) {
        log.warn("访问 AI 服务失败（连接/读超时）：{}", e.getMessage());
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.GATEWAY_TIMEOUT);
        pd.setTitle("AI 服务不可达");
        pd.setDetail("连接或读取超时，请检查 Python 服务是否在运行");
        return pd;
    }

    /**
     * 登录失败。
     *
     * <p>★ 无论是"没有这个用户"还是"口令错了"，都返回同一句话 —— 否则这个接口
     * 就变成一份免费的账号枚举器。
     */
    @ExceptionHandler(AuthService.BadCredentials.class)
    public ProblemDetail onBadCredentials(AuthService.BadCredentials e) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.UNAUTHORIZED);
        pd.setTitle("登录失败");
        pd.setDetail(e.getMessage());
        return pd;
    }

    /**
     * 角色没登记 ACL 映射。
     *
     * <p>★ 用 403 而不是 500：这是「身份为真、权限未定义」，属于配置问题，
     * 不是服务端崩溃。给 500 会让监控把它计入系统故障，淹没真正的事故。
     */
    @ExceptionHandler(AclProjector.UnknownRole.class)
    public ProblemDetail onUnknownRole(AclProjector.UnknownRole e) {
        log.warn("拒绝请求：{}", e.getMessage());
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.FORBIDDEN);
        pd.setTitle("权限未定义");
        pd.setDetail("当前角色没有对应的数据权限，请联系管理员");
        return pd;
    }

    /**
     * 配额用尽。
     *
     * <p>★ 带 {@code Retry-After}：客户端能据此提示"明天再来"，而不是无脑重试
     * （重试还会继续消耗计数，因为配额是"先扣再判"）。
     */
    @ExceptionHandler(ChatService.QuotaExceeded.class)
    public ResponseEntity<ProblemDetail> onQuotaExceeded(ChatService.QuotaExceeded e) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.TOO_MANY_REQUESTS);
        pd.setTitle("今日配额已用尽");
        pd.setDetail(e.getMessage());
        long seconds = Math.max(1, Duration.between(LocalDateTime.now(ZONE),
                LocalDate.now(ZONE).plusDays(1).atStartOfDay()).getSeconds());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", String.valueOf(seconds))
                .body(pd);
    }

    /**
     * 控制器里 `throw new ResponseStatusException(...)` 的那些（比如"只有 hr/admin 能上传"）。
     *
     * <p>★ 不做这个映射时，Spring 会返回它自己的 `{timestamp,status,error,path}` 结构，
     * **没有 detail 字段** —— 前端读不到原因，只能显示"请求失败（403）"。
     * 于是"你没权限"变成了一句无信息的话，而这正是最该说清的一类错误。
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ProblemDetail onResponseStatus(ResponseStatusException e) {
        ProblemDetail pd = ProblemDetail.forStatus(e.getStatusCode());
        pd.setDetail(e.getReason());
        return pd;
    }
}
