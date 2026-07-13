package com.lingframe.example.mall.config;

import cn.hutool.extra.servlet.ServletUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.servlet.http.HttpServletRequest;
import java.util.concurrent.CompletableFuture;

@Aspect
@Component
@Slf4j
@RequiredArgsConstructor
public class AuditLogAspect {

    private final JdbcTemplate jdbcTemplate;

    @Around("@annotation(auditLog)")
    public Object around(ProceedingJoinPoint joinPoint, AuditLog auditLog) throws Throwable {
        long startTime = System.currentTimeMillis();
        String status = "SUCCESS";
        
        String ip = "127.0.0.1";
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes != null) {
            HttpServletRequest request = attributes.getRequest();
            ip = ServletUtil.getClientIP(request);
        }

        String operator = "ANONYMOUS";
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated() && !"anonymousUser".equals(authentication.getPrincipal())) {
            operator = authentication.getName();
        }

        try {
            Object result = joinPoint.proceed();
            
            if ("LOGIN".equals(auditLog.action()) && "ANONYMOUS".equals(operator) && joinPoint.getArgs().length > 0) {
                operator = String.valueOf(joinPoint.getArgs()[0]);
            }
            
            return result;
        } catch (Throwable e) {
            status = "FAIL";
            throw e;
        } finally {
            long duration = System.currentTimeMillis() - startTime;
            
            final String finalOperator = operator;
            final String finalIp = ip;
            final String finalStatus = status;
            
            CompletableFuture.runAsync(() -> {
                try {
                    String sql = "INSERT INTO t_audit_log (operator, action, resource, ip, status, duration) VALUES (?, ?, ?, ?, ?, ?)";
                    jdbcTemplate.update(sql, finalOperator, auditLog.action(), auditLog.resource(), finalIp, finalStatus, duration);
                } catch (Exception e) {
                    log.error("Failed to async save audit log", e);
                }
            });
        }
    }
}
