package com.legacyrecon.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 04.3 错误统一 RFC 7807 problem+json（生产级）：
 * 4xx 客户端错误返回可读 detail；5xx 不泄漏内部细节（detail 走服务端日志）。
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail onIllegalArgument(IllegalArgumentException e) {
        log.warn("请求参数错误：{}", e.getMessage());
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        pd.setTitle("Bad Request");
        pd.setDetail(e.getMessage());
        return pd;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail onValidation(MethodArgumentNotValidException e) {
        log.warn("请求校验失败：{}", e.getMessage());
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        pd.setTitle("Validation Failed");
        pd.setDetail("请求参数不合法");
        return pd;
    }

    @ExceptionHandler({
            MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class,
            HttpRequestMethodNotSupportedException.class})
    public ProblemDetail onBadRequest(Exception e) {
        log.warn("客户端请求不合法：{}", e.getMessage());
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        pd.setTitle("Bad Request");
        pd.setDetail(e.getMessage() == null ? "请求格式不合法" : e.getMessage());
        return pd;
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Void> onNoResource(NoResourceFoundException e) {
        // 静态资源/未知路径返回 404，无响应体（避免 framework 默认错误页）
        return ResponseEntity.notFound().build();
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail onGeneric(Exception e) {
        // 5xx：细节不外泄，仅记录完整堆栈到服务端日志
        log.error("未处理异常", e);
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR);
        pd.setTitle("Internal Server Error");
        pd.setDetail("服务内部错误，详情见服务端日志");
        return pd;
    }
}