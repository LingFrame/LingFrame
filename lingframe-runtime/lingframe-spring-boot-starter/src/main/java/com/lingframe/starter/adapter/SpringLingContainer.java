package com.lingframe.starter.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lingframe.api.context.LingContext;
import com.lingframe.api.ling.Ling;
import com.lingframe.api.ling.LingProbe;
import com.lingframe.api.storage.ManagedDataSourceRegistry;
import com.lingframe.core.context.DefaultLingContext;
import com.lingframe.core.ling.BusinessInterfaceFilter;
import com.lingframe.core.ling.LingServiceRegistrar;
import com.lingframe.core.ling.LingServiceRegistry;
import com.lingframe.core.spi.LingContainer;
import com.lingframe.core.config.LingFrameInfo;
import com.lingframe.starter.processor.LingReferenceInjector;
import com.lingframe.core.spi.LingUnloadHook;
import com.lingframe.starter.resource.LingScanCachePurger;
import com.lingframe.starter.spi.LingContextCustomizer;
import com.lingframe.starter.spi.SpringAwareUnloadHook;
import com.lingframe.starter.util.JacksonCacheEvictUtil;
import com.lingframe.starter.web.LingWebMetadataExtractor;
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
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Spring 容器适配器。
 * 用于在 Spring Boot 应用中集成灵珑容器。
 */
@Slf4j
public class SpringLingContainer implements LingContainer {

    private static final String DISABLE_LOGGING_SHUTDOWN_HOOK = "logging.register-shutdown-hook=false";

    // 🔥 非 final：stop() 时必须清空，否则 builder 持有 ResourceLoader → ClassLoader 引用链
    // 跨线程读写的字段必须 volatile：start() 由部署线程写入，stop()/isActive() 可能由其他线程读取，
    // 没有 volatile 时读线程可能长期看到旧值，导致 stop() 后 isActive() 仍返回 true 等竞态。
    private volatile SpringApplicationBuilder builder;
    private volatile ConfigurableApplicationContext context;
    private volatile ClassLoader classLoader; // 非 final，以便在 stop() 中清除
    private volatile String version;
    private volatile WebInterfaceManager webInterfaceManager;
    private volatile List<String> excludedPackages;
    private volatile List<LingContextCustomizer> customizers; // 新增定制器
    // 保存 Context 以便 stop 时使用
    private volatile LingContext lingContext;
    private volatile ApplicationContext mainContext; // 🔥 主容器引用
    private final List<LingUnloadHook> unloadHooks; // 🔥 卸载钩子列表

    private volatile File sourceFile;

    /**
     * 容器是否已停止的幂等标志。
     * <p>
     * 使用 {@link AtomicBoolean} 配合 {@code compareAndSet(false, true)} 实现原子占位，
     * 消除 volatile boolean 的非原子 check-then-act 竞态（并发 stop() 双重清理风险）。
     * <p>
     * stop() 第一次调用时 CAS 成功，再次调用直接返回；
     * isActive() 基于 {@link #get()} 判定，避免在 context 已置 null 后仍尝试访问它。
     */
    private final AtomicBoolean stopped = new AtomicBoolean(false);

    /**
     * 灵核只读配置门面（可选注入）。
     * <p>
     * 替代静态穿透 {@code LingFrameConfig.current()} 读隐式注册开关；
     * 装配链未注入时兜底默认 true（与 {@code LingFrameConfig} builder 默认值一致）。
     */
    private LingFrameInfo lingFrameInfo;

    /**
     * 受管数据源独立总线（灵核 starter 装配的灵核级单例，分支 B 拉取受管数据源用）。
     * <p>
     * 经 {@code SpringContainerFactory.create} 从灵核主容器注入；纯 core / native 场景
     * 无该 Bean 时为 null，分支 B 走不到注入（与现状一致）。
     */
    private volatile ManagedDataSourceRegistry managedDataSourceRegistry;

    /**
     * 注入灵核只读配置门面，替代静态穿透。
     */
    public void setLingFrameInfo(LingFrameInfo lingFrameInfo) {
        this.lingFrameInfo = lingFrameInfo;
    }

    /**
     * 注入受管数据源独立总线（灵核侧装配）。
     *
     * @param managedDataSourceRegistry 受管数据源总线（可为 null：灵核 0 存储/native 场景）
     */
    public void setManagedDataSourceRegistry(ManagedDataSourceRegistry managedDataSourceRegistry) {
        this.managedDataSourceRegistry = managedDataSourceRegistry;
    }

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

            // 受管数据源总线注入灵元容器（灵核级单例实例）：
            // 模式 3 存储灵元（供给端）可经 @Autowired/@Resource 拿到本 Bean，把自建数据源
            // 以 dataSourceId 注册到总线供其他业务灵元共享；灵核 0 存储/native 场景为 null 不注册
            if (managedDataSourceRegistry != null) {
                context.registerBean("lingManagedDataSourceRegistry", ManagedDataSourceRegistry.class,
                        () -> managedDataSourceRegistry, bd -> bd.setPrimary(true));
            }

            log.info("Injecting core beans for ling [{}]: LingContext, LingReferenceInjector", lingId);

            // 自动配置灵元数据源（分支 A 独立库 / 分支 B 受管共享；存储灵元经 datasource-id 供给总线）
            LingDataSourceRegistrar.register(context, lingClassLoader, lingId, managedDataSourceRegistry);

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

        // 🔥 统一注册器：收敛「显式 @LingService + 隐式接口」双轨注册逻辑到 core，
        // 删除原散在 SpringLingContainer 的 isBusinessInterface 黑名单。
        // 生态环境排除前缀由 Registrar 静态参考值提供，用户排除项透传 BusinessInterfaceFilter。
        LingServiceRegistry registry = coreCtx.getLingServiceRegistry();
        BusinessInterfaceFilter interfaceFilter = BusinessInterfaceFilter.builder()
                .ecosystemExcluded(LingServiceRegistrar.defaultEcosystemExcluded())
                .userExcluded(excludedPackages)
                .build();
        LingServiceRegistrar registrar = new LingServiceRegistrar(
                registry, interfaceFilter,
                lingFrameInfo == null ? true : lingFrameInfo.isImplicitRegistration(),
                coreCtx);

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

                // 🔥 委派给统一注册器：显式 @LingService + 隐式接口一并处理
                registrar.register(lingId, bean, targetClass);
            } catch (Exception e) {
                log.warn("Error scanning bean {} for LingServices", beanName, e);
            }
        }
    }

    /**
     * 扫描并注册 @RestController。
     * 注解解析下沉至 {@link LingWebMetadataExtractor}；提取完成后有界 purge 注解静态缓存。
     */
    private void scanAndRegisterControllers() {
        if (!(lingContext instanceof DefaultLingContext)) {
            return;
        }
        String lingId = lingContext.getLingId();
        boolean prefixWithLingId = lingFrameInfo != null && lingFrameInfo.isPrefixWithLingId();
        LingWebMetadataExtractor extractor = new LingWebMetadataExtractor(version, classLoader, context, prefixWithLingId);

        Map<String, Object> controllers = context.getBeansWithAnnotation(RestController.class);
        for (Map.Entry<String, Object> entry : controllers.entrySet()) {
            String beanName = entry.getKey();
            Object bean = entry.getValue();
            try {
                Class<?> targetClass = AopUtils.getTargetClass(bean);
                if (targetClass.getClassLoader() != classLoader) {
                    continue;
                }
                List<WebInterfaceMetadata> metadataList =
                        extractor.extractFromController(lingId, beanName, bean, targetClass);
                for (WebInterfaceMetadata metadata : metadataList) {
                    metadata.minimizeCoreStrongReferences();
                    log.info("🌍 [LingFrame Web] Found Controller: {} [{}]",
                            metadata.getHttpMethod(), metadata.getUrlPattern());
                    if (webInterfaceManager != null) {
                        webInterfaceManager.registerSync(metadata);
                    }
                }
            } catch (Exception e) {
                log.error("Failed to parse controller bean in ling: {}", lingId, e);
            }
        }

        // 缩短扫描写入的注解/反射静态缓存存活窗口（不替代卸载全量 cleaner）
        LingScanCachePurger.purgeAnnotationCachesAfterMetadataExtract(lingId, classLoader);
    }

    @Override
    public void stop() {
        // 🔥 幂等：CAS 原子占位，避免并发/重复 stop() 引发二次清理
        if (!stopped.compareAndSet(false, true)) {
            return;
        }

        ConfigurableApplicationContext closedContext = this.context;
        // lingId 提升到方法作用域：后续引用清理日志需要用到，但此时 lingContext 可能已被置 null
        String lingId = (lingContext != null) ? lingContext.getLingId() : "unknown";
        try {
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
                        Map<String, ObjectMapper> coreObjectMappers = this.mainContext.getBeansOfType(ObjectMapper.class);
                        for (ObjectMapper om : coreObjectMappers.values()) {
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
        } finally {
            // 🔥 第二阶段清理会由 DefaultLingLifecycleEngine 调用 unloadHook.cleanup()
            // cleanup 签名不变（仅 lingId + classLoader），无需在此预置 context 引用

            // 彻底断开所有强引用，辅助 GC 回收 ClassLoader
            // 必须在 finally 中执行，确保即使清理过程抛异常也能断开引用
            this.builder = null; 
            this.context = null; 
            this.mainContext = null; 
            this.classLoader = null;
            this.lingContext = null;
            this.webInterfaceManager = null;
            this.excludedPackages = null;
            this.customizers = null;
            this.version = null;
            this.sourceFile = null;
            log.debug("[{}] Container references cleared", lingId);
        }
    }

    @Override
    public boolean isActive() {
        // 🔥 基于 stopped 标志判定，避免在 context 已置 null 后仍尝试访问它
        if (stopped.get()) {
            return false;
        }
        ConfigurableApplicationContext ctx = this.context;
        return ctx != null && ctx.isActive();
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

    @Override
    public String probe(String contractId) {
        String lingInfo = (lingContext != null && lingContext.getLingId() != null)
                ? (lingContext.getLingId() + (version != null ? ("@" + version) : ""))
                : (version != null ? version : "ling-container");

        // 1. 若灵元内部注册了自定义 LingProbe Bean，优先委托其执行
        if (context != null && context.isActive()) {
            try {
                LingProbe customProbe = context.getBean(LingProbe.class);
                if (customProbe != null) {
                    return customProbe.probe(contractId);
                }
            } catch (Exception ignored) {
                // 未注册自定义 LingProbe，走默认标准探针
            }
        }

        // 2. 默认标准探针：在灵元自身容器中输出标准健康探测日志
        log.info("[{}] [LingProbe] Health probe ping received, container is ACTIVE, contract: {}",
                lingInfo, contractId);
        return "OK";
    }

    /**
     * 从 URL 中提取纯路径部分，剥离协议前缀（file:/、jar:file:/ 等）。
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
