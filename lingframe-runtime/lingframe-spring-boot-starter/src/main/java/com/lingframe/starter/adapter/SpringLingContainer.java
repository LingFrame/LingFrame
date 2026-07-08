package com.lingframe.starter.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lingframe.api.annotation.Auditable;
import com.lingframe.api.annotation.LingService;
import com.lingframe.api.annotation.RequiresPermission;
import com.lingframe.api.context.LingContext;
import com.lingframe.api.ling.Ling;
import com.lingframe.core.context.DefaultLingContext;
import com.lingframe.core.spi.LingContainer;
import com.lingframe.core.governance.GovernanceStrategy;
import com.lingframe.starter.processor.LingReferenceInjector;
import com.lingframe.core.spi.LingUnloadHook;
import com.lingframe.starter.spi.LingContextCustomizer;
import com.lingframe.starter.spi.SpringAwareUnloadHook;
import com.lingframe.starter.util.JacksonCacheEvictUtil;
import com.lingframe.starter.web.WebInterfaceManager;
import com.lingframe.starter.web.WebInterfaceMetadata;
import java.io.File;
import java.net.URL;
import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.annotation.AnnotationAttributes;
import org.springframework.util.ReflectionUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Spring 容器适配器。
 * 用于在 Spring Boot 应用中集成灵珑容器。
 */
@Slf4j
public class SpringLingContainer implements LingContainer {

    private static final String DISABLE_LOGGING_SHUTDOWN_HOOK = "logging.register-shutdown-hook=false";

    private static final RequestMethod[] DEFAULT_HTTP_METHODS = new RequestMethod[] {
            RequestMethod.GET,
            RequestMethod.HEAD,
            RequestMethod.POST,
            RequestMethod.PUT,
            RequestMethod.PATCH,
            RequestMethod.DELETE,
            RequestMethod.OPTIONS,
            RequestMethod.TRACE
    };

    // 🔥 非 final：stop() 时必须清空，否则 builder 持有 ResourceLoader → ClassLoader 引用链
    private SpringApplicationBuilder builder;
    private ConfigurableApplicationContext context;
    private ClassLoader classLoader; // 非 final，以便在 stop() 中清除
    private String version;
    private WebInterfaceManager webInterfaceManager;
    private List<String> excludedPackages;
    private List<LingContextCustomizer> customizers; // 新增定制器
    // 保存 Context 以便 stop 时使用
    private LingContext lingContext;
    private ApplicationContext mainContext; // 🔥 主容器引用
    private final List<LingUnloadHook> unloadHooks; // 🔥 卸载钩子列表

    private File sourceFile;

    // 保留原构造函数，向后兼容测试用例
    public SpringLingContainer(SpringApplicationBuilder builder,
                               ClassLoader classLoader,
                               WebInterfaceManager webInterfaceManager,
                               List<String> excludedPackages,
                               List<LingContextCustomizer> customizers,
                               ApplicationContext mainContext,
                               List<LingUnloadHook> unloadHooks,
                               String version) {
        this(builder, classLoader, webInterfaceManager, excludedPackages, customizers, mainContext, unloadHooks, version, null);
    }

    // 新增构造函数，支持传入部署物理资源路径
    public SpringLingContainer(SpringApplicationBuilder builder,
                               ClassLoader classLoader,
                               WebInterfaceManager webInterfaceManager,
                               List<String> excludedPackages,
                               List<LingContextCustomizer> customizers,
                               ApplicationContext mainContext,
                               List<LingUnloadHook> unloadHooks,
                               String version,
                               File sourceFile) {
        this.builder = builder;
        this.classLoader = classLoader;
        this.webInterfaceManager = webInterfaceManager;
        this.excludedPackages = excludedPackages != null ? excludedPackages : Collections.emptyList();
        this.customizers = customizers != null ? customizers : Collections.emptyList();
        this.mainContext = mainContext;
        this.unloadHooks = unloadHooks != null ? unloadHooks : Collections.emptyList();
        this.version = version;
        this.sourceFile = sourceFile;
    }

    @Override
    public void start(LingContext lingContext) {
        this.lingContext = lingContext;
        // 禁用 Boot 日志系统的全局 shutdown hook，避免其通过全局 handler 持有灵元 ClassLoader。
        builder.properties(DISABLE_LOGGING_SHUTDOWN_HOOK);

        // 劫持线程上下文类加载器（TCCL）
        Thread t = Thread.currentThread();
        ClassLoader old = t.getContextClassLoader();
        t.setContextClassLoader(classLoader);
        try {
            // 添加初始化器：在 Spring 启动前注册关键组件和应用定制器
            builder.initializers(applicationContext -> {
                if (applicationContext instanceof GenericApplicationContext) {
                    GenericApplicationContext gac = (GenericApplicationContext) applicationContext;
                    registerBeans(gac, classLoader);
                }

                if (applicationContext instanceof ConfigurableApplicationContext) {
                    for (LingContextCustomizer customizer : customizers) {
                        try {
                            customizer.customize(lingContext, (ConfigurableApplicationContext) applicationContext);
                        } catch (Exception e) {
                            log.error("Error applying context customizer: " + customizer.getClass().getName(), e);
                        }
                    }
                }
            });
            // 启动 Spring
            this.context = builder.run();

            try {
                Ling ling = this.context.getBean(Ling.class);
                log.info("Triggering onStart for ling: {}", lingContext.getLingId());
                ling.onStart(lingContext);
            } catch (Exception e) {
                log.warn("No Ling entry point found in ling: {}", lingContext.getLingId());
            }

            // 扫描 @LingService 并注册到 Core
            try {
                scheduleServiceRegistration();
            } catch (Exception e) {
                log.warn("Failed to register LingServices for ling: {}", lingContext.getLingId(), e);
            }
        } finally {
            t.setContextClassLoader(old);
        }
    }

    /**
     * 手动注册核心 Bean
     */
    private void registerBeans(GenericApplicationContext context, ClassLoader lingClassLoader) {
        if (lingContext instanceof DefaultLingContext) {
            DefaultLingContext coreCtx = (DefaultLingContext) lingContext;
            String lingId = lingContext.getLingId();

            // 注册 LingContext 并设为 @Primary
            context.registerBean(LingContext.class, () -> coreCtx,
                    bd -> bd.setPrimary(true));

            // 注册灵元专用的 LingReferenceInjector，传给它是 context
            context.registerBean(LingReferenceInjector.class, () -> new LingReferenceInjector(lingId, coreCtx));

            log.info("Injecting core beans for ling [{}]: LingContext, LingReferenceInjector", lingId);

            // 自动配置灵元独立数据源
            LingDataSourceRegistrar.register(context, lingClassLoader, lingId);

            // 🔥 注入灵元私有的 HandlerAdapter，防止 DTO 等类污染灵核缓存
            context.registerBean(RequestMappingHandlerAdapter.class, () -> {
                RequestMappingHandlerAdapter adapter = new RequestMappingHandlerAdapter();
                // 必须设置 MessageConverters，否则无法处理 JSON
                // 补全 StringHttpMessageConverter 以处理基础响应，增强健壮性
                List<HttpMessageConverter<?>> converters = new ArrayList<>();
                converters.add(new StringHttpMessageConverter());
                converters.add(new MappingJackson2HttpMessageConverter());
                adapter.setMessageConverters(converters);
                
                // 重要：必须显式调用以加载默认的 ArgumentResolvers 和 ReturnValueHandlers
                // 否则类似 @RequestBody 的参数解析逻辑不会生效
                adapter.setApplicationContext(context);
                adapter.afterPropertiesSet();
                return adapter;
            });
        }
    }

    /**
     * 延迟服务注册
     */
    private void scheduleServiceRegistration() {
        log.info("All beans initialized, registering LingServices for ling: {}", lingContext.getLingId());
        scanAndRegisterLingServices();
        scanAndRegisterControllers();
    }

    /**
     * 扫描协议服务
     */
    private void scanAndRegisterLingServices() {
        if (!(lingContext instanceof DefaultLingContext)) {
            log.warn("LingContext is not instance of DefaultLingContext, cannot register services.");
            return;
        }
        DefaultLingContext coreCtx = (DefaultLingContext) lingContext;
        String lingId = lingContext.getLingId();

        // 获取容器中所有 Bean 的名称
        String[] beanNames = context.getBeanDefinitionNames();

        for (String beanName : beanNames) {
            try {
                Object bean = context.getBean(beanName);
                // 处理 AOP 代理，获取目标类
                Class<?> targetClass = AopUtils.getTargetClass(bean);

                // 防御式过滤：如果 targetClass 不是当前灵元的 ClassLoader 加载的，就跳过它，防止重复和误注册外部 Bean
                if (targetClass.getClassLoader() != classLoader) {
                    continue;
                }

                // 物理路径比对防误扫（防开发环境下 ClassLoader 穿透引起的多版本 Bean 串用）
                if (sourceFile != null) {
                    URL classUrl = targetClass.getResource(targetClass.getSimpleName() + ".class");
                    if (classUrl != null) {
                        // 剥离 URL 协议前缀（file:/ 或 jar:file:/），统一为纯路径后再比对
                        String classPath = extractPathFromUrl(classUrl).replace("\\", "/").toLowerCase();
                        String sourcePath = sourceFile.getAbsolutePath().replace("\\", "/").toLowerCase();
                        if (!classPath.contains(sourcePath)) {
                            log.warn("[{}] Ignored scan-leaked Bean [{}] (loaded from: {}, sourceFile: {})",
                                    lingId, beanName, classPath, sourcePath);
                            continue;
                        }
                    }
                }

                // 1. 显式 @LingService 注册 (FQSID: [LingID]:[ShortID])
                ReflectionUtils.doWithMethods(targetClass, method -> {
                    LingService lingService = AnnotatedElementUtils.findMergedAnnotation(method, LingService.class);
                    if (lingService != null) {
                        String shortId = lingService.id();
                        String fqsid = lingId + ":" + shortId;
                        coreCtx.registerProtocolService(fqsid, bean, method);
                    }
                });

                // 2. 隐式接口注册 (FQSID: [InterfaceName]:[MethodName])
                // 支持 @LingReference 跨灵元调用
                for (Class<?> iface : targetClass.getInterfaces()) {
                    if (isBusinessInterface(iface)) {
                        for (Method ifaceMethod : iface.getMethods()) {
                            try {
                                Method implMethod = targetClass.getMethod(
                                        ifaceMethod.getName(), ifaceMethod.getParameterTypes());
                                String canonicalFqsid = lingId + ":" + iface.getName();
                                coreCtx.registerProtocolService(canonicalFqsid, bean, implMethod);
                            } catch (NoSuchMethodException ignored) {
                            }
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("Error scanning bean {} for LingServices", beanName, e);
            }
        }
    }

    /**
     * 判断是否为业务接口（排除 Java/Spring/常见框架接口 + 用户配置排除项）
     */
    private boolean isBusinessInterface(Class<?> iface) {
        String name = iface.getName();

        // 内置排除规则
        if (name.startsWith("java.") ||
                name.startsWith("javax.") ||
                name.startsWith("jakarta.") ||
                name.startsWith("org.springframework.") ||
                name.startsWith("org.slf4j.") ||
                name.startsWith("io.micrometer.") ||
                name.startsWith("com.zaxxer.") ||
                name.startsWith("lombok.") ||
                name.startsWith("com.lingframe.api.context.") ||
                name.startsWith("com.lingframe.api.ling.") ||
                name.startsWith("com.lingframe.starter.")) {
            return false;
        }

        // 用户配置的排除规则
        for (String prefix : excludedPackages) {
            if (name.startsWith(prefix)) {
                return false;
            }
        }

        return true;
    }

    /**
     * 扫描并注册 @RestController（原生 Spring MVC 注册）
     */
    private void scanAndRegisterControllers() {
        if (!(lingContext instanceof DefaultLingContext))
            return;
        String lingId = lingContext.getLingId();

        // 获取所有 @RestController
        Map<String, Object> controllers = context.getBeansWithAnnotation(RestController.class);

        for (Map.Entry<String, Object> entry : controllers.entrySet()) {
            String beanName = entry.getKey();
            Object bean = entry.getValue();
            try {
                Class<?> targetClass = AopUtils.getTargetClass(bean);

                // 解析类级 @RequestMapping
                RequestMapping classMapping = AnnotatedElementUtils.findMergedAnnotation(targetClass,
                        RequestMapping.class);

                // 遍历方法
                ReflectionUtils.doWithMethods(targetClass, method -> {
                    // 查找 RequestMapping (包含 GetMapping, PostMapping 等)
                    RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
                    if (mapping != null) {
                        registerControllerMappings(lingId, beanName, bean, method, classMapping, mapping);
                    }
                });
            } catch (Exception e) {
                log.error("Failed to parse controller bean in ling: {}", lingId, e);
            }
        }
    }


    private void registerControllerMappings(String lingId, String beanName, Object bean, Method method,
                                            RequestMapping classMapping, RequestMapping mapping) {
        String permission = resolvePermission(method);
        Auditable auditAnn = AnnotatedElementUtils.findMergedAnnotation(method, Auditable.class);
        SwaggerOp swaggerOp = extractSwaggerOperation(method);
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

                WebInterfaceMetadata metadata = buildWebInterfaceMetadata(
                        lingId, beanName, bean, method, fullPath, httpMethod,
                        params, headers, consumes, produces, permission, auditInfo, swaggerOp, requestMappingInfo);
                metadata.minimizeHostReferences();

                log.info("🌍 [LingFrame Web] Found Controller: {} [{}]", httpMethod, fullPath);
                if (webInterfaceManager != null) {
                    webInterfaceManager.registerSync(metadata);
                }
            }
        }
    }

    /**
     * 解析方法所需的权限标识：优先从 @RequiresPermission 注解获取，回退到 GovernanceStrategy 推断。
     */
    private String resolvePermission(Method method) {
        RequiresPermission permAnn = AnnotatedElementUtils.findMergedAnnotation(method, RequiresPermission.class);
        if (permAnn != null) {
            return permAnn.value();
        }
        return GovernanceStrategy.inferPermission(method);
    }

    /**
     * 解析审计信息：有 @Auditable 注解则强制审计，写操作自动审计，其余不审计。
     */
    private AuditInfo resolveAuditInfo(Auditable auditAnn, String httpMethod, String fullPath, String methodName) {
        if (auditAnn != null) {
            return new AuditInfo(true, auditAnn.action());
        }
        if (isWriteMethod(httpMethod)) {
            return new AuditInfo(true, httpMethod + " " + fullPath);
        }
        return new AuditInfo(false, methodName);
    }

    /**
     * 提取 Swagger @Operation 注解信息（summary/description/tags）。
     * 使用字符串类名动态搜索，避免对 swagger-annotations 的强编译依赖。
     *
     * @return SwaggerOp 持有者，字段可能为 null
     */
    private SwaggerOp extractSwaggerOperation(Method method) {
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
        return op;
    }

    /**
     * 构建 Spring RequestMappingInfo，包含路径、方法、参数、请求头、consumes/produces 约束。
     */
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

    /**
     * 构建 WebInterfaceMetadata，聚合路由、权限、审计、Swagger 等全部元信息。
     */
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
                .classLoader(this.classLoader)
                .lingApplicationContext(this.context)
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
                .requestMappingInfo(requestMappingInfo)
                .build();
    }

    /** 审计信息持有者（shouldAudit + auditAction） */
    private static final class AuditInfo {
        final boolean shouldAudit;
        final String auditAction;
        AuditInfo(boolean shouldAudit, String auditAction) {
            this.shouldAudit = shouldAudit;
            this.auditAction = auditAction;
        }
    }

    /** Swagger @Operation 信息持有者 */
    private static final class SwaggerOp {
        String summary;
        String description;
        String[] tags;
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
            return new String[] {""};
        }
        if (mapping.path().length > 0) {
            return mapping.path();
        }
        if (mapping.value().length > 0) {
            return mapping.value();
        }
        return new String[] {""};
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
        String[] copy = new String[source.length];
        System.arraycopy(source, 0, copy, 0, source.length);
        return copy;
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

    @Override
    public void stop() {
        ConfigurableApplicationContext closedContext = this.context;
        // lingId 提升到方法作用域：后续引用清理日志需要用到，但此时 lingContext 可能已被置 null
        String lingId = (lingContext != null) ? lingContext.getLingId() : "unknown";
        if (closedContext != null && closedContext.isActive()) {

            try {
                Ling ling = closedContext.getBean(Ling.class);
                log.info("Triggering onStop for ling: {}", lingId);
                ling.onStop(lingContext);
            } catch (Exception e) {
                // 忽略，可能没有入口类
            }

            // 注销 Web 接口元数据
            if (webInterfaceManager != null) {
                webInterfaceManager.unregisterSync(lingId, this.classLoader);
            }

            // ✅ 从主容器获取 ObjectMapper，而不是靠 @Autowired
            try {
            if (this.mainContext != null) {
                try {
                    // 1. 清理灵核主容器中的 ObjectMapper 缓存 (最重要的，因为网关走这里)
                    Map<String, ObjectMapper> hostOms = this.mainContext.getBeansOfType(ObjectMapper.class);
                    for (ObjectMapper om : hostOms.values()) {
                        JacksonCacheEvictUtil.evictByClassLoader(om, this.classLoader);
                    }
                    
                    // 2. 清理灵元内部容器中的 ObjectMapper 缓存 (防止内部引用不释放)
                    Map<String, ObjectMapper> lingOms = closedContext.getBeansOfType(ObjectMapper.class);
                    for (ObjectMapper om : lingOms.values()) {
                        JacksonCacheEvictUtil.evictByClassLoader(om, this.classLoader);
                    }
                    log.info("[{}] Jackson caches evicted successfully", lingId);
                } catch (Exception e) {
                    log.warn("[{}] Failed to evict Jackson caches", lingId, e);
                }
            }
            } catch (Exception e) {
                log.warn("Failed to clear Jackson cache", e);
            }

            // 🔥 第一阶段清理：在 Context 关闭前执行 preCleanup
            // 上下文以参数传入，Hook 不再持有可变单例字段，消除并发卸载竞态
            for (LingUnloadHook hook : unloadHooks) {
                if (hook instanceof SpringAwareUnloadHook) {
                    try {
                        SpringAwareUnloadHook awareHook = (SpringAwareUnloadHook) hook;
                        awareHook.preCleanup(lingId, this.mainContext, closedContext);
                    } catch (Exception e) {
                        log.debug("Failed to invoke preCleanup on unload hook: {}", hook.getClass().getName(), e);
                    }
                }
            }
            // 5. 关闭上下文 (核心隔离点)
            try {
                closedContext.close();
                log.info("[{}] Spring ApplicationContext closed successfully", lingId);
            } catch (Exception e) {
                // 🔥 关键修复：隔离上下文关闭异常，防止阻断整机卸载
                log.error("[{}] Error during Spring ApplicationContext close, forcing reference cleanup", lingId, e);
            }
        }

        // 🔥 第二阶段清理会由 DefaultLingLifecycleEngine 调用 unloadHook.cleanup()
        // cleanup 签名不变（仅 lingId + classLoader），无需在此预置 context 引用

        // 彻底断开所有强引用，辅助 GC 回收 ClassLoader
        this.builder = null; 
        this.context = null; 
        this.mainContext = null; 
        this.classLoader = null;
        this.lingContext = null;
        this.webInterfaceManager = null;
        this.excludedPackages = null;
        this.customizers = null;
        this.version = null;
        log.debug("[{}] Container references cleared", lingId);
    }

    @Override
    public boolean isActive() {
        return context != null && context.isActive();
    }

    @Override
    public <T> T getBean(Class<T> type) {
        if (!isActive())
            return null;
        try {
            return context.getBean(type);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public Object getBean(String beanName) {
        if (!isActive())
            return null;
        try {
            return context.getBean(beanName);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public String[] getBeanNames() {
        if (!isActive())
            return new String[0];
        return context.getBeanDefinitionNames();
    }

    @Override
    public ClassLoader getClassLoader() {
        return this.classLoader;
    }

    /**
     * 从 URL 中提取纯路径部分，剥离协议前缀（file:/、jar:file:/ 等）。
     * <p>
     * 示例：
     * <ul>
     *   <li>{@code file:/E:/Codes/app/Service.class} → {@code E:/Codes/app/Service.class}</li>
     *   <li>{@code jar:file:/E:/Codes/app.jar!/Service.class} → {@code E:/Codes/app.jar!/Service.class}</li>
     * </ul>
     */
    private static String extractPathFromUrl(URL url) {
        String spec = url.toString();
        // 处理 jar:file:/...!/... 形式
        if (spec.startsWith("jar:")) {
            spec = spec.substring(4);
        }
        // 处理 file:/... 形式（Windows: file:/C:/...；Linux: file:/home/...）
        if (spec.startsWith("file:")) {
            spec = spec.substring(5);
        }
        return spec;
    }
}
