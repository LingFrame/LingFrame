package com.lingframe.starter.web;

import com.lingframe.api.annotation.Auditable;
import com.lingframe.api.annotation.RequiresPermission;
import com.lingframe.core.governance.GovernanceStrategy;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.annotation.AnnotationAttributes;
import org.springframework.util.ReflectionUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 灵元 Web 路由元数据提取器（注册时一次性）。
 * <p>
 * 从灵元 Controller 的 {@code Class}/{@code Method} 提炼纯字符串/基本类型字段写入
 * {@link WebInterfaceMetadata}，供灵核侧路由与 OpenAPI 使用。
 * <p>
 * <b>CL 现实（诚实边界）</b>：本类由灵核 ClassLoader 加载，内部使用的
 * {@link AnnotatedElementUtils} 是父委派的灵核 Spring，扫描仍会写入进程级静态注解缓存。
 * 提取器的价值是「一次提取 + 运行时真源」，不是「灵元侧扫描 = 隔离缓存」。
 * 注册完成后应由 {@link com.lingframe.starter.resource.LingScanCachePurger}
 * 做有界 purge，卸载时仍走全量 cleaner。
 */
public final class LingWebMetadataExtractor {

    private static final RequestMethod[] DEFAULT_HTTP_METHODS = new RequestMethod[]{
            RequestMethod.GET,
            RequestMethod.HEAD,
            RequestMethod.POST,
            RequestMethod.PUT,
            RequestMethod.PATCH,
            RequestMethod.DELETE,
            RequestMethod.OPTIONS,
            RequestMethod.TRACE
    };

    private final String version;
    private final ClassLoader lingClassLoader;
    private final ApplicationContext lingApplicationContext;

    public LingWebMetadataExtractor(String version,
                                    ClassLoader lingClassLoader,
                                    ApplicationContext lingApplicationContext) {
        this.version = version;
        this.lingClassLoader = lingClassLoader;
        this.lingApplicationContext = lingApplicationContext;
    }

    /**
     * 从单个 Controller bean 提取全部路由元数据（可能多 path × 多 method）。
     * 返回的条目仍含 targetBean/targetMethod 强引用，调用方应 {@code minimizeCoreStrongReferences()}。
     */
    public List<WebInterfaceMetadata> extractFromController(String lingId,
                                                            String beanName,
                                                            Object bean,
                                                            Class<?> targetClass) {
        List<WebInterfaceMetadata> result = new ArrayList<>();
        if (targetClass == null || bean == null) {
            return result;
        }
        RequestMapping classMapping = AnnotatedElementUtils.findMergedAnnotation(targetClass, RequestMapping.class);
        SwaggerClassTag classTag = extractSwaggerClassTag(targetClass);

        ReflectionUtils.doWithMethods(targetClass, method -> {
            RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
            if (mapping != null) {
                result.addAll(buildMappings(lingId, beanName, bean, method, classMapping, mapping, classTag));
            }
        });
        return result;
    }

    private List<WebInterfaceMetadata> buildMappings(String lingId,
                                                     String beanName,
                                                     Object bean,
                                                     Method method,
                                                     RequestMapping classMapping,
                                                     RequestMapping mapping,
                                                     SwaggerClassTag classTag) {
        List<WebInterfaceMetadata> list = new ArrayList<>();
        String permission = resolvePermission(method);
        Auditable auditAnn = AnnotatedElementUtils.findMergedAnnotation(method, Auditable.class);
        SwaggerOp swaggerOp = extractSwaggerOperation(method, classTag);
        Set<String> fullPaths = resolveFullPaths(lingId, classMapping, mapping);
        RequestMethod[] httpMethods = resolveHttpMethods(classMapping, mapping);
        String[] params = resolveParams(classMapping, mapping);
        String[] headers = resolveHeaders(classMapping, mapping);
        String[] consumes = resolveConsumes(classMapping, mapping);
        String[] produces = resolveProduces(classMapping, mapping);

        for (String fullPath : fullPaths) {
            for (RequestMethod requestMethod : httpMethods) {
                String httpMethod = requestMethod.name();
                AuditInfo auditInfo = resolveAuditInfo(auditAnn, httpMethod, fullPath, method.getName());
                RequestMappingInfo requestMappingInfo = buildRequestMappingInfo(
                        fullPath, requestMethod, params, headers, consumes, produces);
                list.add(buildWebInterfaceMetadata(
                        lingId, beanName, bean, method, fullPath, httpMethod,
                        params, headers, consumes, produces, permission, auditInfo, swaggerOp, requestMappingInfo));
            }
        }
        return list;
    }

    private String resolvePermission(Method method) {
        RequiresPermission permAnn = AnnotatedElementUtils.findMergedAnnotation(method, RequiresPermission.class);
        if (permAnn != null) {
            return permAnn.value();
        }
        return GovernanceStrategy.inferPermission(method);
    }

    private AuditInfo resolveAuditInfo(Auditable auditAnn, String httpMethod, String fullPath, String methodName) {
        if (auditAnn != null) {
            return new AuditInfo(true, auditAnn.action());
        }
        if (isWriteMethod(httpMethod)) {
            return new AuditInfo(true, httpMethod + " " + fullPath);
        }
        return new AuditInfo(false, methodName);
    }

    private SwaggerClassTag extractSwaggerClassTag(Class<?> controllerClass) {
        SwaggerClassTag tag = new SwaggerClassTag();
        if (controllerClass == null) {
            return tag;
        }
        try {
            AnnotationAttributes tagAttr = AnnotatedElementUtils.findMergedAnnotationAttributes(
                    controllerClass, "io.swagger.v3.oas.annotations.tags.Tag", false, false);
            if (tagAttr != null) {
                tag.name = tagAttr.getString("name");
                tag.description = tagAttr.getString("description");
            }
        } catch (Throwable ignored) {
            // 类路径无 Swagger 时忽略
        }
        return tag;
    }

    private SwaggerOp extractSwaggerOperation(Method method, SwaggerClassTag classTag) {
        SwaggerOp op = new SwaggerOp();
        try {
            AnnotationAttributes opAttr = AnnotatedElementUtils.findMergedAnnotationAttributes(
                    method, "io.swagger.v3.oas.annotations.Operation", false, false);
            if (opAttr != null) {
                op.summary = opAttr.getString("summary");
                op.description = opAttr.getString("description");
                op.tags = opAttr.getStringArray("tags");
            }
        } catch (Throwable ignored) {
            // 即使类路径无 Swagger 也不影响基本路由注册
        }
        if (classTag != null) {
            if (classTag.description != null && !classTag.description.isEmpty()) {
                op.tagDescription = classTag.description;
            }
            if ((op.tags == null || op.tags.length == 0)
                    && classTag.name != null && !classTag.name.isEmpty()) {
                op.tags = new String[]{classTag.name};
            }
        }
        return op;
    }

    private RequestMappingInfo buildRequestMappingInfo(String fullPath, RequestMethod requestMethod,
                                                       String[] params, String[] headers,
                                                       String[] consumes, String[] produces) {
        RequestMappingInfo.Builder mappingBuilder = RequestMappingInfo
                .paths(fullPath)
                .methods(requestMethod);
        if (params.length > 0) {
            mappingBuilder.params(params);
        }
        if (headers.length > 0) {
            mappingBuilder.headers(headers);
        }
        if (consumes.length > 0) {
            mappingBuilder.consumes(consumes);
        }
        if (produces.length > 0) {
            mappingBuilder.produces(produces);
        }
        return mappingBuilder.build();
    }

    private WebInterfaceMetadata buildWebInterfaceMetadata(
            String lingId, String beanName, Object bean, Method method,
            String fullPath, String httpMethod,
            String[] params, String[] headers, String[] consumes, String[] produces,
            String permission, AuditInfo auditInfo, SwaggerOp swaggerOp,
            RequestMappingInfo requestMappingInfo) {
        return WebInterfaceMetadata.builder()
                .lingId(lingId)
                .version(version)
                .targetBeanName(beanName)
                .targetBean(bean)
                .targetClassName(resolveControllerClass(bean, method).getName())
                .targetMethodName(method.getName())
                .targetMethodParameterTypeNames(resolveParameterTypeNames(method))
                .targetMethod(method)
                .classLoader(lingClassLoader)
                .lingApplicationContext(lingApplicationContext)
                .urlPattern(fullPath)
                .httpMethod(httpMethod)
                .params(copyStringArray(params))
                .headers(copyStringArray(headers))
                .consumes(copyStringArray(consumes))
                .produces(copyStringArray(produces))
                .requiredPermission(permission)
                .shouldAudit(auditInfo.shouldAudit)
                .auditAction(auditInfo.auditAction)
                .opSummary(swaggerOp.summary)
                .opDescription(swaggerOp.description)
                .opTags(swaggerOp.tags != null ? Arrays.copyOf(swaggerOp.tags, swaggerOp.tags.length) : null)
                .opTagDescription(swaggerOp.tagDescription)
                .requestMappingInfo(requestMappingInfo)
                .build();
    }

    private Set<String> resolveFullPaths(String lingId, RequestMapping classMapping, RequestMapping methodMapping) {
        String[] classPaths = resolvePaths(classMapping);
        String[] methodPaths = resolvePaths(methodMapping);
        LinkedHashSet<String> fullPaths = new LinkedHashSet<>();
        for (String classPath : classPaths) {
            for (String methodPath : methodPaths) {
                fullPaths.add(normalizePath("/" + lingId + "/" + classPath + "/" + methodPath));
            }
        }
        return fullPaths;
    }

    private Class<?> resolveControllerClass(Object bean, Method method) {
        Class<?> targetClass = bean != null ? AopUtils.getTargetClass(bean) : null;
        return targetClass != null ? targetClass : method.getDeclaringClass();
    }

    private String[] resolvePaths(RequestMapping mapping) {
        if (mapping == null) {
            return new String[]{""};
        }
        if (mapping.path().length > 0) {
            return mapping.path();
        }
        if (mapping.value().length > 0) {
            return mapping.value();
        }
        return new String[]{""};
    }

    private RequestMethod[] resolveHttpMethods(RequestMapping classMapping, RequestMapping methodMapping) {
        if (methodMapping != null && methodMapping.method().length > 0) {
            return methodMapping.method();
        }
        if (classMapping != null && classMapping.method().length > 0) {
            return classMapping.method();
        }
        return DEFAULT_HTTP_METHODS;
    }

    private String[] resolveParams(RequestMapping classMapping, RequestMapping methodMapping) {
        return mergeExpressions(classMapping != null ? classMapping.params() : new String[0],
                methodMapping != null ? methodMapping.params() : new String[0]);
    }

    private String[] resolveHeaders(RequestMapping classMapping, RequestMapping methodMapping) {
        return mergeExpressions(classMapping != null ? classMapping.headers() : new String[0],
                methodMapping != null ? methodMapping.headers() : new String[0]);
    }

    private String[] resolveConsumes(RequestMapping classMapping, RequestMapping methodMapping) {
        if (methodMapping != null && methodMapping.consumes().length > 0) {
            return copyStringArray(methodMapping.consumes());
        }
        return classMapping != null ? copyStringArray(classMapping.consumes()) : new String[0];
    }

    private String[] resolveProduces(RequestMapping classMapping, RequestMapping methodMapping) {
        if (methodMapping != null && methodMapping.produces().length > 0) {
            return copyStringArray(methodMapping.produces());
        }
        return classMapping != null ? copyStringArray(classMapping.produces()) : new String[0];
    }

    private String[] mergeExpressions(String[] first, String[] second) {
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        addExpressions(merged, first);
        addExpressions(merged, second);
        return merged.toArray(new String[0]);
    }

    private void addExpressions(Set<String> target, String[] source) {
        if (source == null) {
            return;
        }
        for (String expression : source) {
            if (expression == null || expression.trim().isEmpty()) {
                continue;
            }
            target.add(expression);
        }
    }

    private boolean isWriteMethod(String httpMethod) {
        return "POST".equals(httpMethod)
                || "PUT".equals(httpMethod)
                || "PATCH".equals(httpMethod)
                || "DELETE".equals(httpMethod);
    }

    private String normalizePath(String path) {
        String normalized = path.replaceAll("/+", "/");
        if (normalized.isEmpty()) {
            return "/";
        }
        if (!normalized.startsWith("/")) {
            normalized = "/" + normalized;
        }
        if (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private String[] copyStringArray(String[] source) {
        if (source == null || source.length == 0) {
            return new String[0];
        }
        return Arrays.copyOf(source, source.length);
    }

    private String[] resolveParameterTypeNames(Method method) {
        Class<?>[] parameterTypes = method.getParameterTypes();
        if (parameterTypes.length == 0) {
            return new String[0];
        }
        String[] names = new String[parameterTypes.length];
        for (int i = 0; i < parameterTypes.length; i++) {
            names[i] = parameterTypes[i].getName();
        }
        return names;
    }

    private static final class AuditInfo {
        final boolean shouldAudit;
        final String auditAction;

        AuditInfo(boolean shouldAudit, String auditAction) {
            this.shouldAudit = shouldAudit;
            this.auditAction = auditAction;
        }
    }

    private static final class SwaggerClassTag {
        String name;
        String description;
    }

    private static final class SwaggerOp {
        String summary;
        String description;
        String[] tags;
        String tagDescription;
    }
}
