package com.lingframe.starter.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lingframe.api.annotation.Auditable;
import com.lingframe.api.annotation.LingService;
import com.lingframe.api.annotation.RequiresPermission;
import com.lingframe.api.context.LingContext;
import com.lingframe.api.ling.Ling;
import com.lingframe.core.context.DefaultLingContext;
import com.lingframe.core.spi.LingContainer;
import com.lingframe.core.strategy.GovernanceStrategy;
import com.lingframe.starter.processor.LingReferenceInjector;
import com.lingframe.core.spi.ResourceGuard;
import com.lingframe.starter.spi.LingContextCustomizer;
import com.lingframe.starter.spi.SpringAwareResourceGuard;
import com.lingframe.starter.util.JacksonCacheEvictUtil;
import com.lingframe.starter.web.WebInterfaceManager;
import com.lingframe.starter.web.WebInterfaceMetadata;
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
    private final List<ResourceGuard> resourceGuards; // 🔥 资源守卫列表

    public SpringLingContainer(SpringApplicationBuilder builder,
                               ClassLoader classLoader,
                               WebInterfaceManager webInterfaceManager,
                               List<String> excludedPackages,
                               List<LingContextCustomizer> customizers,
                               ApplicationContext mainContext,
                               List<ResourceGuard> resourceGuards,
                               String version) {
        this.builder = builder;
        this.classLoader = classLoader;
        this.webInterfaceManager = webInterfaceManager;
        this.excludedPackages = excludedPackages != null ? excludedPackages : Collections.emptyList();
        this.customizers = customizers != null ? customizers : Collections.emptyList();
        this.mainContext = mainContext;
        this.resourceGuards = resourceGuards != null ? resourceGuards : Collections.emptyList();
        this.version = version;
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

            // 🔥 注入灵元私有的 HandlerAdapter，防止 DTO 等类污染宿主缓存
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
                                String fqsid = iface.getName() + ":" + ifaceMethod.getName();
                                coreCtx.registerProtocolService(fqsid, bean, implMethod);
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

    /**
     * 解析单个方法并生成元数据（简化版，不再解析参数）
     */
    private void registerControllerMethod(String lingId, String beanName, Object bean, Method method,
                                          RequestMapping classMapping, RequestMapping mapping) {
        // 请求路径按 `/lingId/classUrl/methodUrl` 规则拼接
        String baseUrl = classMapping != null && classMapping.path().length > 0 ? classMapping.path()[0] : "";
        String methodUrl = mapping.path().length > 0 ? mapping.path()[0] : "";
        String fullPath = null;

        // 解析 HTTP 方法
        String httpMethod = mapping.method().length > 0 ? mapping.method()[0].name() : "GET";

        // 智能权限推导
        String permission;
        RequiresPermission permAnn = AnnotatedElementUtils.findMergedAnnotation(method, RequiresPermission.class);
        if (permAnn != null) {
            permission = permAnn.value();
        } else {
            permission = GovernanceStrategy.inferPermission(method);
        }

        // 智能审计推导
        boolean shouldAudit = false;
        String auditAction = method.getName();
        Auditable auditAnn = AnnotatedElementUtils.findMergedAnnotation(method, Auditable.class);

        if (auditAnn != null) {
            shouldAudit = true;
            auditAction = auditAnn.action();
        } else if (!"GET".equals(httpMethod)) {
            shouldAudit = true;
            auditAction = httpMethod + " " + fullPath;
        }

        // 构建简化的元数据（不含参数定义，由 Spring 原生处理）
        WebInterfaceMetadata metadata = WebInterfaceMetadata.builder()
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
                .requiredPermission(permission)
                .shouldAudit(shouldAudit)
                .auditAction(auditAction)
                .build();
        metadata.minimizeHostReferences();

        log.info("🌍 [LingFrame Web] Found Controller: {} [{}]", httpMethod, fullPath);

        // 注册到 WebInterfaceManager
        if (webInterfaceManager != null) {
            webInterfaceManager.registerSync(metadata);
        }
    }

    private void registerControllerMappings(String lingId, String beanName, Object bean, Method method,
                                            RequestMapping classMapping, RequestMapping mapping) {
        String permission;
        RequiresPermission permAnn = AnnotatedElementUtils.findMergedAnnotation(method, RequiresPermission.class);
        if (permAnn != null) {
            permission = permAnn.value();
        } else {
            permission = GovernanceStrategy.inferPermission(method);
        }

        Auditable auditAnn = AnnotatedElementUtils.findMergedAnnotation(method, Auditable.class);
        Set<String> fullPaths = resolveFullPaths(lingId, classMapping, mapping);
        RequestMethod[] httpMethods = resolveHttpMethods(classMapping, mapping);
        String[] params = resolveParams(classMapping, mapping);
        String[] headers = resolveHeaders(classMapping, mapping);
        String[] consumes = resolveConsumes(classMapping, mapping);
        String[] produces = resolveProduces(classMapping, mapping);

        for (String fullPath : fullPaths) {
            for (RequestMethod requestMethod : httpMethods) {
                String httpMethod = requestMethod.name();
                boolean shouldAudit = false;
                String auditAction = method.getName();
                if (auditAnn != null) {
                    shouldAudit = true;
                    auditAction = auditAnn.action();
                } else if (isWriteMethod(httpMethod)) {
                    shouldAudit = true;
                    auditAction = httpMethod + " " + fullPath;
                }

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
                RequestMappingInfo requestMappingInfo = mappingBuilder.build();
                
                String opSummary = null;
                String opDescription = null;
                String[] opTags = null;
                try {
                    // 使用字符串类名动态搜索，避免对 swagger-annotations 的强编译依赖
                    AnnotationAttributes opAttr = AnnotatedElementUtils.findMergedAnnotationAttributes(
                            method, "io.swagger.v3.oas.annotations.Operation", false, false);
                    if (opAttr != null) {
                        opSummary = opAttr.getString("summary");
                        opDescription = opAttr.getString("description");
                        opTags = opAttr.getStringArray("tags");
                    }
                } catch (Throwable ignored) {
                    // 即使类路径无 Swagger 也不影响基本路由注册
                }

                WebInterfaceMetadata metadata = WebInterfaceMetadata.builder()
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
                        .shouldAudit(shouldAudit)
                        .auditAction(auditAction)
                        .opSummary(opSummary)
                        .opDescription(opDescription)
                        .opTags(opTags != null ? Arrays.copyOf(opTags, opTags.length) : null)
                        .requestMappingInfo(requestMappingInfo)
                        .build();
                metadata.minimizeHostReferences();

                log.info("🌍 [LingFrame Web] Found Controller: {} [{}]", httpMethod, fullPath);
                if (webInterfaceManager != null) {
                    webInterfaceManager.registerSync(metadata);
                }
            }
        }
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
        if (context != null && context.isActive()) {
            String lingId = (lingContext != null) ? lingContext.getLingId() : "unknown";

            try {
                Ling ling = this.context.getBean(Ling.class);
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
                    // 1. 清理宿主主容器中的 ObjectMapper 缓存 (最重要的，因为网关走这里)
                    Map<String, ObjectMapper> hostOms = this.mainContext.getBeansOfType(ObjectMapper.class);
                    for (ObjectMapper om : hostOms.values()) {
                        JacksonCacheEvictUtil.evictByClassLoader(om, this.classLoader);
                    }
                    
                    // 2. 清理灵元内部容器中的 ObjectMapper 缓存 (防止内部引用不释放)
                    if (this.context != null) {
                        Map<String, ObjectMapper> lingOms = this.context.getBeansOfType(ObjectMapper.class);
                        for (ObjectMapper om : lingOms.values()) {
                            JacksonCacheEvictUtil.evictByClassLoader(om, this.classLoader);
                        }
                    }
                    log.info("[{}] Jackson caches evicted successfully", lingId);
                } catch (Exception e) {
                    log.warn("[{}] Failed to evict Jackson caches", lingId, e);
                }
            }
            } catch (Exception e) {
                log.warn("清理 Jackson 缓存失败", e);
            }

            // 🔥 第一阶段清理：在 Context 关闭前执行 preCleanup
            for (ResourceGuard guard : resourceGuards) {
                if (guard instanceof SpringAwareResourceGuard) {
                    try {
                        SpringAwareResourceGuard awareGuard = (SpringAwareResourceGuard) guard;
                        awareGuard.setContexts(this.mainContext, this.context);
                        awareGuard.preCleanup(lingId);
                    } catch (Exception e) {
                        log.debug("Failed to invoke preCleanup on resource guard: {}", guard.getClass().getName(), e);
                    }
                }
            }
            // 5. 关闭上下文 (核心隔离点)
            try {
                context.close();
                log.info("[{}] Spring ApplicationContext closed successfully", lingId);
            } catch (Exception e) {
                // 🔥 关键修复：隔离上下文关闭异常，防止阻断整机卸载
                log.error("[{}] Error during Spring ApplicationContext close, forcing reference cleanup", lingId, e);
            }
        }

        // 🔥 第二阶段清理会由 DefaultLingLifecycleEngine 调用 resourceGuard.cleanup()
        // 此处仅确保 context 引用已设置（防御性补充，防止未初始化的 guard 错过注入）
        for (ResourceGuard guard : resourceGuards) {
            if (guard instanceof SpringAwareResourceGuard) {
                try {
                    ((SpringAwareResourceGuard) guard).setContexts(this.mainContext,
                            this.context);
                } catch (Exception ignored) {
                }
            }
        }

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
        log.debug("[{}] Container references cleared", (lingContext != null) ? lingContext.getLingId() : "unknown");
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
}
