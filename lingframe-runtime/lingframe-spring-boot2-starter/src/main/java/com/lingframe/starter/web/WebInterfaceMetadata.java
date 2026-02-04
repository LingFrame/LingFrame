package com.lingframe.starter.web;

import lombok.Builder;
import lombok.Data;
import org.springframework.context.ApplicationContext;

import java.lang.reflect.Method;

/**
 * 插件 Web 接口元数据
 * 存储插件 Controller 的路由信息和治理元数据
 * 注：参数绑定由 Spring MVC 原生处理，无需存储参数信息
 */
@Data
@Builder
public class WebInterfaceMetadata {
    // 插件信息
    private String pluginId;
    private Object targetBean; // 插件里的 Controller Bean 实例
    private Method targetMethod; // 插件里的目标方法
    private ClassLoader classLoader; // 插件的类加载器
    private ApplicationContext pluginApplicationContext; // 持有插件的 Spring 上下文

    // 路由信息
    private String urlPattern; // 完整 URL，例如 /plugin-id/users/{id}
    private String httpMethod; // GET, POST, etc.

    // 预先计算好的治理元数据
    private String requiredPermission;
    private boolean shouldAudit;
    private String auditAction;
}