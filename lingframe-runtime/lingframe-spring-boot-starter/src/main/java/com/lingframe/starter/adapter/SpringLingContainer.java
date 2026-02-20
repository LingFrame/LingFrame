package com.lingframe.starter.adapter;

import com.lingframe.api.annotation.Auditable;
import com.lingframe.api.annotation.LingService;
import com.lingframe.api.annotation.RequiresPermission;
import com.lingframe.api.context.LingContext;
import com.lingframe.api.ling.Ling;
import com.lingframe.core.context.CoreLingContext;
import com.lingframe.core.ling.LingManager;
import com.lingframe.core.spi.LingContainer;
import com.lingframe.core.strategy.GovernanceStrategy;
import com.lingframe.starter.processor.LingReferenceInjector;
import com.lingframe.starter.web.WebInterfaceManager;
import com.lingframe.starter.web.WebInterfaceMetadata;
import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.io.support.SpringFactoriesLoader;
import org.springframework.util.ReflectionUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.context.support.AbstractApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MutablePropertySources;

@Slf4j
public class SpringLingContainer implements LingContainer {

    // 🔥 非 final：stop() 时必须清空，否则 builder 持有 ResourceLoader → ClassLoader 引用链
    private SpringApplicationBuilder builder;
    private ConfigurableApplicationContext context;
    private ClassLoader classLoader; // 非 final，以便在 stop() 中清除
    private WebInterfaceManager webInterfaceManager;
    private List<String> excludedPackages;
    // 保存 Context 以便 stop 时使用
    private LingContext lingContext;

    // 🔥 新增：持有灵核 Context 引用，用于清理
    private final ConfigurableApplicationContext hostContext;
    private final RequestMappingHandlerAdapter hostAdapter;

    public SpringLingContainer(SpringApplicationBuilder builder,
                                 ClassLoader classLoader,
                                 WebInterfaceManager webInterfaceManager,
                                 List<String> excludedPackages,
                                 ConfigurableApplicationContext hostContext,
                                 RequestMappingHandlerAdapter hostAdapter) {
        this.builder = builder;
        this.classLoader = classLoader;
        this.webInterfaceManager = webInterfaceManager;
        this.excludedPackages = excludedPackages != null ? excludedPackages : Collections.emptyList();
        this.hostContext = hostContext;
        this.hostAdapter = hostAdapter;
    }

    @Override
    public void start(LingContext lingContext) {
        this.lingContext = lingContext;

        // TCCL 劫持
        Thread t = Thread.currentThread();
        ClassLoader old = t.getContextClassLoader();
        t.setContextClassLoader(classLoader);
        try {
            // 添加初始化器：在 Spring 启动前注册关键组件
            builder.initializers(applicationContext -> {
                if (applicationContext instanceof GenericApplicationContext) {
                    GenericApplicationContext gac = (GenericApplicationContext) applicationContext;
                    registerBeans(gac, classLoader);
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
        if (lingContext instanceof CoreLingContext) {
            CoreLingContext coreCtx = (CoreLingContext)lingContext;
            LingManager lingManager = coreCtx.getLingManager();
            String lingId = lingContext.getLingId();

            // 注册 LingManager
            context.registerBean(LingManager.class, () -> lingManager);

            // 注册 LingContext 并设为 @Primary
            context.registerBean(LingContext.class, () -> coreCtx,
                    bd -> bd.setPrimary(true));

            // 注册单元专用的 LingReferenceInjector
            context.registerBean(LingReferenceInjector.class, () -> new LingReferenceInjector(lingId, lingManager));

            log.info("Injecting core beans for ling [{}]: LingManager, LingReferenceInjector", lingId);

            // 自动配置单元独立数据源
            LingDataSourceRegistrar.register(context, lingClassLoader, lingId);
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
        if (!(lingContext instanceof CoreLingContext)) {
            log.warn("LingContext is not instance of CoreLingContext, cannot register services.");
            return;
        }
        LingManager lingManager = ((CoreLingContext) lingContext).getLingManager();
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
                        lingManager.registerProtocolService(lingId, fqsid, bean, method);
                    }
                });

                // 2. 隐式接口注册 (FQSID: [InterfaceName]:[MethodName])
                // 支持 @LingReference 跨单元调用
                for (Class<?> iface : targetClass.getInterfaces()) {
                    if (isBusinessInterface(iface)) {
                        for (Method ifaceMethod : iface.getMethods()) {
                            try {
                                Method implMethod = targetClass.getMethod(
                                        ifaceMethod.getName(), ifaceMethod.getParameterTypes());
                                String fqsid = iface.getName() + ":" + ifaceMethod.getName();
                                lingManager.registerProtocolService(lingId, fqsid, bean, implMethod);
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
        if (!(lingContext instanceof CoreLingContext))
            return;
        String lingId = lingContext.getLingId();

        // 获取所有 @RestController
        Map<String, Object> controllers = context.getBeansWithAnnotation(RestController.class);

        for (Object bean : controllers.values()) {
            try {
                Class<?> targetClass = AopUtils.getTargetClass(bean);

                // 解析类级 @RequestMapping
                String baseUrl = "";
                RequestMapping classMapping = AnnotatedElementUtils.findMergedAnnotation(targetClass,
                        RequestMapping.class);
                if (classMapping != null && classMapping.path().length > 0) {
                    baseUrl = classMapping.path()[0];
                }

                // 遍历方法
                String finalBaseUrl = baseUrl;
                ReflectionUtils.doWithMethods(targetClass, method -> {
                    // 查找 RequestMapping (包含 GetMapping, PostMapping 等)
                    RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
                    if (mapping != null) {
                        registerControllerMethod(lingId, bean, method, finalBaseUrl, mapping);
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
    private void registerControllerMethod(String lingId, Object bean, Method method,
                                          String baseUrl, RequestMapping mapping) {
        // URL 拼接: /lingId/classUrl/methodUrl
        String methodUrl = mapping.path().length > 0 ? mapping.path()[0] : "";
        String fullPath = ("/" + lingId + "/" + baseUrl + "/" + methodUrl).replaceAll("/+", "/");

        // HTTP Method
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
                .targetBean(bean)
                .targetMethod(method)
                .classLoader(this.classLoader)
                .lingApplicationContext(this.context)
                .urlPattern(fullPath)
                .httpMethod(httpMethod)
                .requiredPermission(permission)
                .shouldAudit(shouldAudit)
                .auditAction(auditAction)
                .build();

        log.info("🌍 [LingFrame Web] Found Controller: {} [{}]", httpMethod, fullPath);

        // 注册到 WebInterfaceManager
        if (webInterfaceManager != null) {
            webInterfaceManager.register(metadata);
        }
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
                webInterfaceManager.unregister(lingId);
            }

            // 🔥 清理 Environment 的 PropertySource，防止 OriginTrackedValue 泄漏
            try {
                Environment rawEnv = context.getEnvironment();
                if (rawEnv instanceof ConfigurableEnvironment) {
                    ConfigurableEnvironment env = (ConfigurableEnvironment) rawEnv;
                    MutablePropertySources sources = env.getPropertySources();
                    // 复制名称列表避免 ConcurrentModificationException
                    List<String> names = new ArrayList<>();
                    sources.forEach(ps -> names.add(ps.getName()));
                    names.forEach(sources::remove);
                    log.debug("[{}] Cleared {} PropertySources from ling Environment", lingId, names.size());
                }
            } catch (Exception e) {
                log.debug("[{}] Failed to clear PropertySources: {}", lingId, e.getMessage());
            }

            // 🔥 清理 ApplicationEventMulticaster.retrieverCache，防止
            // AvailabilityChangeEvent.source 泄漏
            try {
                Object multicaster = context
                        .getBean(AbstractApplicationContext.APPLICATION_EVENT_MULTICASTER_BEAN_NAME);
                if (multicaster != null) {
                    // 获取 AbstractApplicationEventMulticaster.retrieverCache 字段
                    Field retrieverCacheField = multicaster.getClass().getSuperclass()
                            .getDeclaredField("retrieverCache");
                    retrieverCacheField.setAccessible(true);
                    Object cache = retrieverCacheField.get(multicaster);
                    if (cache instanceof Map<?, ?>) {
                        ((Map<?, ?>)cache).clear();
                        log.debug("[{}] Cleared ApplicationEventMulticaster.retrieverCache", lingId);
                    }
                }
            } catch (NoSuchBeanDefinitionException e) {
                log.trace("[{}] No ApplicationEventMulticaster bean found", lingId);
            } catch (NoSuchFieldException e) {
                // 尝试直接在当前类查找
                try {
                    Object multicaster = context
                            .getBean(AbstractApplicationContext.APPLICATION_EVENT_MULTICASTER_BEAN_NAME);
                    Field retrieverCacheField = multicaster.getClass().getDeclaredField("retrieverCache");
                    retrieverCacheField.setAccessible(true);
                    Object cache = retrieverCacheField.get(multicaster);
                    if (cache instanceof Map<?, ?>) {
                        ((Map<?, ?>)cache).clear();
                        log.debug("[{}] Cleared ApplicationEventMulticaster.retrieverCache (direct)", lingId);
                    }
                } catch (Exception ex) {
                    log.trace("[{}] retrieverCache field not accessible: {}", lingId, ex.getMessage());
                }
            } catch (Exception e) {
                log.debug("[{}] Failed to clear ApplicationEventMulticaster cache: {}", lingId, e.getMessage());
            }

            context.close();
        }

        // 🔥 关键：清除所有对单元的引用，防止泄漏
        this.builder = null; // SpringApplicationBuilder 持有 ResourceLoader → ClassLoader
        this.context = null; // ApplicationContext 持有 BeanFactory → 所有 Bean → Class → ClassLoader
        this.classLoader = null;
        this.lingContext = null;
        this.webInterfaceManager = null;
        this.excludedPackages = null;
        this.lingContext = null;
    }

    /**
     * 清理 SpringFactoriesLoader 的静态缓存
     * 这是单元 ClassLoader 泄漏的主要原因之一
     */
    private void clearSpringFactoriesCache(ClassLoader lingClassLoader) {
        try {
            // Spring Framework 5.x / 6.x
            Field cacheField = ReflectionUtils.findField(
                    org.springframework.core.io.support.SpringFactoriesLoader.class,
                    "cache"
            );
            if (cacheField != null) {
                ReflectionUtils.makeAccessible(cacheField);
                Map<?, ?> cache = (Map<?, ?>) ReflectionUtils.getField(cacheField, null);
                if (cache != null) {
                    cache.remove(lingClassLoader);
                    log.debug("Cleared SpringFactoriesLoader cache for: {}", lingClassLoader);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to clear SpringFactoriesLoader cache", e);
        }

        try {
            // Spring Boot 3.x 可能有额外的 forDefaultResourceLocation 缓存
            // SpringFactoriesLoader 内部可能有多个缓存字段，全部清理
            Field[] fields = SpringFactoriesLoader.class.getDeclaredFields();
            for (Field field : fields) {
                if (Map.class.isAssignableFrom(field.getType()) &&
                    Modifier.isStatic(field.getModifiers())) {
                    ReflectionUtils.makeAccessible(field);
                    Map<?, ?> map = (Map<?, ?>) ReflectionUtils.getField(field, null);
                    if (map != null) {
                        Object removed = map.remove(lingClassLoader);
                        if (removed != null) {
                            log.debug("Cleared static cache field '{}' for ling ClassLoader", field.getName());
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to clear additional SpringFactoriesLoader caches", e);
        }
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