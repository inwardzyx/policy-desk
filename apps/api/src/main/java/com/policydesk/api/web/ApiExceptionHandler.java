package com.policydesk.api.web;

import com.policydesk.api.client.PythonAiClient;
import com.policydesk.api.service.AuthService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.ResourceAccessException;

/**
 * 把"上游出事"翻成可判断的状态码。
 *
 * <p>为什么要单独区分 502 / 504：
 * <ul>
 *   <li><b>502</b> —— AI 服务活着但答了个错（4xx/5xx）。改契约或改配置。</li>
 *   <li><b>504</b> —— 等不到回应（连接/读超时）。改超时或查对方是否卡住。</li>
 * </ul>
 * 两种故障的处置动作完全不同，合并成一个 500 就是让人从日志里猜。
 *
 * <p>★ 上游原文只进日志，不进响应体：里面可能有 Python 的栈信息与内网地址。
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

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
}
