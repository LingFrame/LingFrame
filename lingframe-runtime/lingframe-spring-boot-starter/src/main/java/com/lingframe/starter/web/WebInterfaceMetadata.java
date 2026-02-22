package com.lingframe.starter.web;

import lombok.Builder;
import lombok.Data;
import org.springframework.context.ApplicationContext;

import java.lang.reflect.Method;

/**
 * 单元 Web 接口元数据
 * 存储单元 Controller 的路由信息和治理元数据
 * 注：参数绑定由 Spring MVC 原生处理，无需存储参数信息
 */
@Data
@Builder
public class WebInterfaceMetadata {
    // 单元信息
    private String lingId;
    private Object targetBean; // 单元里的 Controller Bean 实例
    private Method targetMethod; // 单元里的目标方法
    private ClassLoader classLoader; // 单元的类加载器
    private ApplicationContext lingApplicationContext; // 持有单元的 Spring 上下文

    // 路由信息
    private String urlPattern; // 完整 URL，例如 /Ling-id/users/{id}
    private String httpMethod; // GET, POST, etc.

    // 预先计算好的治理元数据
    private String requiredPermission;
    private boolean shouldAudit;
    private String auditAction;

    /**
     * 🔥 卸载时清理引用，帮助 GC
     */
    public void clearReferences() {
        this.targetBean = null;
        this.targetMethod = null;
        this.classLoader = null;
        this.lingApplicationContext = null;
    }
}