package com.lingframe.dashboard.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.HashMap;
import java.util.Map;

/**
 * 全局异常处理：屏蔽堆栈信息泄露，统一返回结构化错误响应
 */
@Slf4j
@RestControllerAdvice(basePackages = "com.lingframe.dashboard")
@ConditionalOnProperty(prefix = "lingframe.dashboard", name = "enabled", havingValue = "true", matchIfMissing = false)
public class DashboardExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, Object> handleBadRequest(IllegalArgumentException e) {
        log.debug("请求参数错误: {}", e.getMessage());
        Map<String, Object> result = new HashMap<>();
        result.put("success", false);
        result.put("message", e.getMessage() != null ? e.getMessage() : "参数错误");
        return result;
    }

    @ExceptionHandler(IllegalStateException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, Object> handleConflict(IllegalStateException e) {
        log.debug("状态冲突: {}", e.getMessage());
        Map<String, Object> result = new HashMap<>();
        result.put("success", false);
        result.put("message", e.getMessage() != null ? e.getMessage() : "操作冲突");
        return result;
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Map<String, Object> handleGeneral(Exception e) {
        // 生产环境不暴露堆栈，仅记录日志
        log.error("Dashboard 内部错误: {}", e.getClass().getSimpleName(), e);
        Map<String, Object> result = new HashMap<>();
        result.put("success", false);
        result.put("message", "服务内部错误，请稍后重试");
        return result;
    }
}
