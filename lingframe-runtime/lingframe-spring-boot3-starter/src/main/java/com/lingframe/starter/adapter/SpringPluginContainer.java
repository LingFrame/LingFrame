package com.lingframe.starter.adapter;

import com.lingframe.api.annotation.Auditable;
import com.lingframe.api.annotation.LingService;
import com.lingframe.api.annotation.RequiresPermission;
import com.lingframe.api.context.PluginContext;
import com.lingframe.api.plugin.LingPlugin;
import com.lingframe.core.context.CorePluginContext;
import com.lingframe.core.plugin.PluginManager;
import com.lingframe.core.spi.PluginContainer;
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
import org.springframework.util.ReflectionUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Slf4j
public class SpringPluginContainer implements PluginContainer {

    private final SpringApplicationBuilder builder;
    private ConfigurableApplicationContext context;
    private final ClassLoader classLoader;
    private final WebInterfaceManager webInterfaceManager;
    private final List<String> excludedPackages;
    // 保存 Context 以便 stop 时使用
    private PluginContext pluginContext;

    public SpringPluginContainer(SpringApplicationBuilder builder, ClassLoader classLoader,
            WebInterfaceManager webInterfaceManager, List<String> excludedPackages) {
        this.builder = builder;
        this.classLoader = classLoader;
        this.webInterfaceManager = webInterfaceManager;
        this.excludedPackages = excludedPackages != null ? excludedPackages : Collections.emptyList();
    }

    @Override
    public void start(PluginContext pluginContext) {
        this.pluginContext = pluginContext;

        // TCCL 劫持
        Thread t = Thread.currentThread();
        ClassLoader old = t.getContextClassLoader();
        t.setContextClassLoader(classLoader);
        try {
            // 添加初始化器：在 Spring 启动前注册关键组件
            builder.initializers(applicationContext -> {
                if (applicationContext instanceof GenericApplicationContext gac) {
                    registerBeans(gac, classLoader);
                }
            });
            // 启动 Spring
            this.context = builder.run();

            try {
                LingPlugin plugin = this.context.getBean(LingPlugin.class);
                log.info("Triggering onStart for plugin: {}", pluginContext.getPluginId());
                plugin.onStart(pluginContext);
            } catch (Exception e) {
                log.warn("No LingPlugin entry point found in plugin: {}", pluginContext.getPluginId());
            }

            // 扫描 @LingService 并注册到 Core
            try {
                scheduleServiceRegistration();
            } catch (Exception e) {
                log.warn("Failed to register LingServices for plugin: {}", pluginContext.getPluginId(), e);
            }
        } finally {
            t.setContextClassLoader(old);
        }
    }

    /**
     * 手动注册核心 Bean
     */
    private void registerBeans(GenericApplicationContext context, ClassLoader pluginClassLoader) {
        if (pluginContext instanceof CorePluginContext coreCtx) {
            PluginManager pluginManager = coreCtx.getPluginManager();
            String pluginId = pluginContext.getPluginId();

            // 注册 PluginManager
            context.registerBean(PluginManager.class, () -> pluginManager);

            // 注册 PluginContext 并设为 @Primary
            context.registerBean(PluginContext.class, () -> coreCtx,
                    bd -> bd.setPrimary(true));

            // 注册插件专用的 LingReferenceInjector
            context.registerBean(LingReferenceInjector.class, () -> new LingReferenceInjector(pluginId, pluginManager));

            log.info("Injecting core beans for plugin [{}]: PluginManager, LingReferenceInjector", pluginId);

            // 自动配置插件独立数据源
            PluginDataSourceRegistrar.register(context, pluginClassLoader, pluginId);
        }
    }

    /**
     * 延迟服务注册
     */
    private void scheduleServiceRegistration() {
        log.info("All beans initialized, registering LingServices for plugin: {}", pluginContext.getPluginId());
        scanAndRegisterLingServices();
        scanAndRegisterControllers();
    }

    /**
     * 扫描协议服务
     */
    private void scanAndRegisterLingServices() {
        if (!(pluginContext instanceof CorePluginContext)) {
            log.warn("PluginContext is not instance of CorePluginContext, cannot register services.");
            return;
        }
        PluginManager pluginManager = ((CorePluginContext) pluginContext).getPluginManager();
        String pluginId = pluginContext.getPluginId();

        // 获取容器中所有 Bean 的名称
        String[] beanNames = context.getBeanDefinitionNames();

        for (String beanName : beanNames) {
            try {
                Object bean = context.getBean(beanName);
                // 处理 AOP 代理，获取目标类
                Class<?> targetClass = AopUtils.getTargetClass(bean);

                // 1. 显式 @LingService 注册 (FQSID: [PluginID]:[ShortID])
                ReflectionUtils.doWithMethods(targetClass, method -> {
                    LingService lingService = AnnotatedElementUtils.findMergedAnnotation(method, LingService.class);
                    if (lingService != null) {
                        String shortId = lingService.id();
                        String fqsid = pluginId + ":" + shortId;
                        pluginManager.registerProtocolService(pluginId, fqsid, bean, method);
                    }
                });

                // 2. 隐式接口注册 (FQSID: [InterfaceName]:[MethodName])
                // 支持 @LingReference 跨插件调用
                for (Class<?> iface : targetClass.getInterfaces()) {
                    if (isBusinessInterface(iface)) {
                        for (Method ifaceMethod : iface.getMethods()) {
                            try {
                                Method implMethod = targetClass.getMethod(
                                        ifaceMethod.getName(), ifaceMethod.getParameterTypes());
                                String fqsid = iface.getName() + ":" + ifaceMethod.getName();
                                pluginManager.registerProtocolService(pluginId, fqsid, bean, implMethod);
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
                name.startsWith("com.lingframe.api.plugin.") ||
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
        if (!(pluginContext instanceof CorePluginContext))
            return;
        String pluginId = pluginContext.getPluginId();

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
                        registerControllerMethod(pluginId, bean, method, finalBaseUrl, mapping);
                    }
                });
            } catch (Exception e) {
                log.error("Failed to parse controller bean in plugin: {}", pluginId, e);
            }
        }
    }

    /**
     * 解析单个方法并生成元数据（简化版，不再解析参数）
     */
    private void registerControllerMethod(String pluginId, Object bean, Method method,
            String baseUrl, RequestMapping mapping) {
        // URL 拼接: /pluginId/classUrl/methodUrl
        String methodUrl = mapping.path().length > 0 ? mapping.path()[0] : "";
        String fullPath = ("/" + pluginId + "/" + baseUrl + "/" + methodUrl).replaceAll("/+", "/");

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
                .pluginId(pluginId)
                .targetBean(bean)
                .targetMethod(method)
                .classLoader(this.classLoader)
                .pluginApplicationContext(this.context)
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
            String pluginId = (pluginContext != null) ? pluginContext.getPluginId() : "unknown";

            try {
                LingPlugin plugin = this.context.getBean(LingPlugin.class);
                log.info("Triggering onStop for plugin: {}", pluginId);
                plugin.onStop(pluginContext);
            } catch (Exception e) {
                // 忽略，可能没有入口类
            }

            // 注销 Web 接口元数据
            if (webInterfaceManager != null) {
                webInterfaceManager.unregister(pluginId);
            }

            context.close();
        }
        this.context = null;
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