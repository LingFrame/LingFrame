package com.lingframe.starter.resource;

import com.lingframe.core.spi.LingUnloadHook;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.SpringVersion;

import com.lingframe.starter.spi.SpringAwareUnloadHook;

/**
 * Spring 生态卸载钩子：唯一注册到协调器的生态桶 Bean。
 * <p>
 * 设计原则（顶层收敛 + 包内拆分）：
 * <ul>
 *   <li>本类只负责 {@code lingContext}/{@code mainContext} 持有与编排骨架，
 *       不含任何清理实现逻辑。</li>
 *   <li>所有清理职责下沉到包级私有 Cleaner，按职责物理拆分，
 *       专用判断器留在各自 Cleaner 内部。</li>
 *   <li>{@code preCleanup}/{@code cleanup} 显式串行调用 Cleaner，
 *       时序绝对受控，避免并行竞态。</li>
 * </ul>
 * <p>
 * Spring 清理方法深度耦合（共享 context、专用判断器互相递归、两阶段严格时序），
 * 因此在顶层保持单一 SPI Bean，不拆成多个 LingUnloadHook。
 */
@Slf4j
public class SpringEcosystemUnloadHook implements SpringAwareUnloadHook {

    /**
     * 当前使用的 Spring Framework 主版本号
     * 其中 5 对应 Spring Boot 2.x，6 对应 Spring Boot 3.x
     */
    private static final int SPRING_MAJOR_VERSION = detectSpringMajorVersion();

    // 包内 Cleaner：每个职责一个，构造期初始化
    private final ExecutorCleaner executorCleaner = new ExecutorCleaner();
    private final DataSourceCleaner dataSourceCleaner = new DataSourceCleaner();
    private final EnvironmentCleaner environmentCleaner = new EnvironmentCleaner();
    private final LifecycleMetadataCleaner lifecycleMetadataCleaner = new LifecycleMetadataCleaner();
    private final SpringStaticCacheCleaner staticCacheCleaner = new SpringStaticCacheCleaner();
    private final JacksonCacheCleaner jacksonCacheCleaner = new JacksonCacheCleaner();
    private final CglibCacheCleaner cglibCacheCleaner = new CglibCacheCleaner(SPRING_MAJOR_VERSION);
    private final ObjenesisCacheCleaner objenesisCacheCleaner = new ObjenesisCacheCleaner();
    private final ElCacheCleaner elCacheCleaner = new ElCacheCleaner();
    private final SpringShutdownHookCleaner shutdownHookCleaner = new SpringShutdownHookCleaner();
    private final BindConverterCacheCleaner bindConverterCacheCleaner = new BindConverterCacheCleaner();
    private final JdkProxyCacheCleaner jdkProxyCacheCleaner = new JdkProxyCacheCleaner();
    private final ApplicationListenerCleaner applicationListenerCleaner = new ApplicationListenerCleaner();

    private static int detectSpringMajorVersion() {
        try {
            String version = SpringVersion.getVersion();
            if (version != null && !version.isEmpty()) {
                int major = Integer.parseInt(version.split("\\.")[0]);
                log.info("Detected Spring Framework version: {} (major: {})", version, major);
                return major;
            }
        } catch (Exception e) {
            log.debug("Failed to detect Spring version: {}", e.getMessage());
        }
        return 5; // 默认 Spring 5
    }

    public SpringEcosystemUnloadHook() {
    }

    // =========================================================================
    // 第一阶段：Context 活跃期预清理
    // =========================================================================

    @Override
    public void preCleanup(String lingId, ApplicationContext mainContext,
                           ConfigurableApplicationContext lingContext) {
        if (lingContext == null || !lingContext.isActive())
            return;

        ClassLoader lingClassLoader = lingContext.getClassLoader();
        log.info("[{}] Starting Spring pre-cleanup,target CL: {}@{}, Spring version: {}.x",
                lingId,
                lingClassLoader == null ? "null" : lingClassLoader.getClass().getSimpleName(),
                Integer.toHexString(System.identityHashCode(lingClassLoader)),
                SPRING_MAJOR_VERSION);

        // 每步独立 try-catch：单步失败不跳过后续步骤（与 cleanup 阶段的 safeCleanup 策略一致）
        safeCleanup(lingId, "preCleanup.executor",
                () -> executorCleaner.shutdown(lingId, lingContext.getBeanFactory()));
        safeCleanup(lingId, "preCleanup.applicationListener",
                () -> applicationListenerCleaner.clear(lingId, mainContext, lingContext));
        safeCleanup(lingId, "preCleanup.lifecycleMetadata",
                () -> lifecycleMetadataCleaner.clear(lingId, lingContext.getBeanFactory(), lingClassLoader));
        safeCleanup(lingId, "preCleanup.environment",
                () -> environmentCleaner.clean(lingId, lingContext));
        safeCleanup(lingId, "preCleanup.dataSource",
                () -> dataSourceCleaner.close(lingId, lingContext.getBeanFactory()));
        safeCleanup(lingId, "preCleanup.propertyAnnotationCache",
                () -> staticCacheCleaner.clearPropertyAnnotationCache(lingId, lingClassLoader, "pre"));
        safeCleanup(lingId, "preCleanup.jackson",
                () -> jacksonCacheCleaner.clear(lingId, mainContext, lingClassLoader, "pre"));
        // 移除 Context 自身注册的 ShutdownHook（必须在 Context 关闭前完成，
        // 否则 close() 后 JVM 退出时 ShutdownHook 仍会触发已关闭 Context 的二次 close）
        safeCleanup(lingId, "preCleanup.shutdownHook",
                () -> shutdownHookCleaner.clearApplicationContextShutdownHook(lingId, lingContext));
    }

    // =========================================================================
    // 第二阶段：cleanup 统一入口
    // =========================================================================

    @Override
    public void cleanup(String lingId, ClassLoader classLoader) {
        log.info("[{}] Starting Spring ecosystem cleanup (Spring {}.x)...",
                lingId, SPRING_MAJOR_VERSION);

        // 1. Spring 框架公开 API 静态缓存
        safeCleanup(lingId, "SpringStaticCache.clearStablePublicCaches",
                () -> staticCacheCleaner.clearStablePublicCaches(lingId, classLoader));
        // 2. SpringFactoriesLoader
        safeCleanup(lingId, "SpringStaticCache.clearSpringFactoriesCache",
                () -> staticCacheCleaner.clearSpringFactoriesCache(lingId, classLoader));
        // 3. Spring ShutdownHook 残留引用（仅 ClassLoader 级清理，Context 级清理已在 preCleanup 完成）
        safeCleanup(lingId, "SpringShutdownHookCleaner",
                () -> shutdownHookCleaner.clear(lingId, classLoader));
        // 4. CGLIB 缓存
        safeCleanup(lingId, "CglibCacheCleaner",
                () -> cglibCacheCleaner.clear(lingId, classLoader));
        // 5. Objenesis 缓存
        safeCleanup(lingId, "ObjenesisCacheCleaner",
                () -> objenesisCacheCleaner.clear(lingId, classLoader));
        // 6. EL 缓存
        safeCleanup(lingId, "ElCacheCleaner",
                () -> elCacheCleaner.clear(lingId, classLoader));
        // 7. BindConverter 静态单例（@ConfigurationProperties 绑定器，持有灵元 PropertyEditor 的 ClassLoader）
        safeCleanup(lingId, "BindConverterCacheCleaner",
                () -> bindConverterCacheCleaner.clear(lingId, classLoader));
        // 8. JDK Proxy 缓存（WeakCache 中 CacheValue 持有代理 Class → ClassLoader 强引用链）
        safeCleanup(lingId, "JdkProxyCacheCleaner",
                () -> jdkProxyCacheCleaner.clear(lingId, classLoader));
    }

    /**
     * 为每个清理步骤提供独立的 try-catch 保护，防止某一步失败导致后续步骤被跳过。
     */
    private void safeCleanup(String lingId, String stepName, Runnable cleanupAction) {
        try {
            log.debug("[{}] Starting cleanup step: {}", lingId, stepName);
            cleanupAction.run();
            log.debug("[{}] Completed cleanup step: {}", lingId, stepName);
        } catch (Exception e) {
            log.warn("[{}] {} cleanup failed, continuing with next step: {}",
                    lingId, stepName, e.getMessage());
        } catch (Throwable t) {
            log.warn("[{}] {} cleanup failed with {}: {}, continuing with next step",
                    lingId, stepName, t.getClass().getSimpleName(), t.getMessage());
        }
    }
}
