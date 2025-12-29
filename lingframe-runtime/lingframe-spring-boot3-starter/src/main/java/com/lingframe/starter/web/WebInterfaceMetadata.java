package com.lingframe.starter.web;

import lombok.Builder;
import lombok.Data;
import org.springframework.context.ApplicationContext;

import java.lang.reflect.Method;
import java.util.List;

@Data
@Builder
public class WebInterfaceMetadata {
    // 插件信息
    private String pluginId;
    private Object targetBean;      // 插件里的 Controller Bean 实例
    private Method targetMethod;    // 插件里的目标方法
    private ClassLoader classLoader; // 插件的类加载器
    private ApplicationContext pluginApplicationContext; // 持有插件的 Spring 上下文

    // 路由信息
    private String urlPattern;      // 完整 URL，例如 /p/user-plugin/users/{id}
    private String httpMethod;      // GET, POST, etc.

    // 参数定义列表（为了后续“三段式”绑定做准备）
    private List<ParamDef> parameters;

    // 🔥预先计算好的治理元数据
    private String requiredPermission;
    private boolean shouldAudit;
    private String auditAction;

    @Data
    @Builder
    public static class ParamDef {
        private String name;        // 参数名 (如 "id")
        private Class<?> type;      // 参数类型 (如 String.class, UserDTO.class)
        private ParamType sourceType; // 来源类型
    }

    public enum ParamType {
        PATH_VARIABLE,
        REQUEST_PARAM,
        REQUEST_BODY,
        SERVLET_REQUEST,
        SERVLET_RESPONSE,
        UNKNOWN
    }
}