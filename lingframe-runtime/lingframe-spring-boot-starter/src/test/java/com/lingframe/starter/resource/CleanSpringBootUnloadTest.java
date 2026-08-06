package com.lingframe.starter.resource;

import com.lingframe.api.config.LingDefinition;
import com.lingframe.api.exception.InvalidArgumentException;
import com.lingframe.core.event.EventBus;
import com.lingframe.core.event.monitor.MonitoringEvents;
import com.lingframe.core.ling.LingLifecycleEngine;
import com.lingframe.core.ling.LingUninstallResult;
import com.lingframe.core.loader.LingManifestLoader;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import java.io.File;
import java.lang.ref.WeakReference;
import java.net.URL;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 干净的 SpringBoot 卸载测试。
 * <p>
 * 不使用 @SpringBootTest，手动启动独立的 SpringBoot 容器，
 * 避免测试框架(JUnit、Mockito、Spring Test)的复杂引用干扰。
 * <p>
 * 测试流程：
 * 1. 启动独立 SpringBoot 进程（非 Web）
 * 2. 部署灵元
 * 3. 卸载灵元
 * 4. 关闭容器并清理引用
 * 5. 验证 ClassLoader 被 GC 回收
 */
@Slf4j
public class CleanSpringBootUnloadTest {

    private static final String LING_ID = "test-ling";

    /**
     * 测试入口：在干净的 SpringBoot 环境中验证 ClassLoader 卸载
     */
    public static void main(String[] args) throws Exception {
        log.info("========================================");
        log.info("开始干净的 SpringBoot 卸载测试");
        log.info("========================================");

        // 1. 启动独立的 SpringBoot 容器（非 Web 模式）
        SpringApplication app = new SpringApplication(LingTestSpringConfiguration.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        
        // 设置测试专用属性
        System.setProperty("spring.main.allow-bean-definition-overriding", "true");
        System.setProperty("lingframe.dev-mode", "true");
        System.setProperty("lingframe.enabled", "true");
        System.setProperty("lingframe.auto-scan", "false");
        
        ConfigurableApplicationContext ctx = null;
        LingLifecycleEngine lifecycleEngine = null;
        EventBus eventBus = null;
        WeakReference<ClassLoader> capturedClassLoaderRef = null;
        ClassLoader originalContextCL = Thread.currentThread().getContextClassLoader();

        try {
            ctx = app.run(args);
            log.info("✅ SpringBoot 容器启动成功");

            // 2. 获取必要的 Bean
            lifecycleEngine = ctx.getBean(LingLifecycleEngine.class);
            eventBus = ctx.getBean(EventBus.class);
            log.info("✅ 获取核心 Bean: LifecycleEngine={}, EventBus={}", 
                    lifecycleEngine.getClass().getSimpleName(), 
                    eventBus.getClass().getSimpleName());

            // 3. 部署灵元
            // 不硬编码绝对路径和版本号：在 lings/ 目录下按通配符查找 lingframe-example-ling-test-*.jar
            File lingFile = findLingTestJar();
            LingDefinition definition = LingManifestLoader.parseDefinition(lingFile);
            if (definition == null) {
                throw new InvalidArgumentException("file", "Not a valid ling package: " + lingFile.getName());
            }

            log.info("📦 开始部署灵元: {} v{}", definition.getId(), definition.getVersion());
            lifecycleEngine.deploy(definition, lingFile, false, Collections.emptyMap());

            // 4. 先订阅泄漏检测事件（必须在卸载之前订阅）
            CountDownLatch leakLatch = new CountDownLatch(1);
            AtomicReference<MonitoringEvents.LeakDetectionEvent> leakEvent = new AtomicReference<>();
            eventBus.subscribeGlobal(MonitoringEvents.LeakDetectionEvent.class, e -> {
                if (LING_ID.equals(e.getLingId())) {
                    leakEvent.set(e);
                    leakLatch.countDown();
                }
            });
            log.info("✅ 已订阅泄漏检测事件");

            // 5. 卸载灵元（触发泄漏检测）
            log.info("🗑️ 开始卸载灵元: {}", definition.getId());
            LingUninstallResult result = lifecycleEngine.undeployWithReport(definition.getId());
            log.info("卸载触发: {}", result.isUninstallTriggered());

            // 6. 等待泄漏检测结果
            boolean received = leakLatch.await(30, TimeUnit.SECONDS);
            MonitoringEvents.LeakDetectionEvent event = null;
            if (received) {
                event = leakEvent.get();
                log.info("泄漏检测结果: collected={}", event != null && event.isCollected());
            } else {
                log.error("⚠️ 超时未收到 LeakDetectionEvent");
            }

            // 7. 先关闭容器（关键！必须在heap dump之前）
            log.info("🧹 关闭 SpringBoot 容器");
            ctx.close();
            ctx = null;
            leakEvent.set(null);
            lifecycleEngine = null;
            eventBus = null;

            // 8. 验证最终结果（此时ApplicationContext已关闭，可准确分析泄漏）
            if (event != null && event.isCollected()) {
                log.info("========================================");
                log.info("✅ SUCCESS: ClassLoader 成功回收");
                log.info("========================================");
            } else {
                log.error("========================================");
                log.error("❌ FAILURE: ClassLoader 未被回收");
                if (event != null) {
                    log.error("检测模式: {}", event.getDetectionMode());
                    log.error("详细信息: {}", event.getMessage());
                } else {
                    log.error("原因: 未收到 LeakDetectionEvent");
                }
                log.error("========================================");
                
                // 此时ApplicationContext已关闭，heap dump能准确反映真正的泄漏源
                ClassLoaderLeakDiagnoser.dumpHeap(CleanSpringBootUnloadTest.class.getName(), true);
            }

        } catch (Exception e) {
            log.error("测试执行失败", e);
            if (ctx != null && ctx.isActive()) {
                ctx.close();
            }
            ClassLoaderLeakDiagnoser.dumpHeap(CleanSpringBootUnloadTest.class.getName() + "-exception", true);
        } finally {
            // 确保测试线程的 ContextClassLoader 恢复
            Thread.currentThread().setContextClassLoader(originalContextCL);

            // 清理系统属性
            System.clearProperty("spring.main.allow-bean-definition-overriding");
            System.clearProperty("lingframe.dev-mode");
            System.clearProperty("lingframe.enabled");
            System.clearProperty("lingframe.auto-scan");
        }
    }

    /**
     * 从 classpath 加载 lingframe-example-ling-test.jar。
     * <p>
     * 不硬编码绝对路径和版本号：jar 文件放在 {@code src/test/resources/} 下，
     * 由 Maven 在 test-compile 阶段复制到 {@code target/test-classes/}，运行时通过 classpath 定位。
     *
     * @return classpath 资源对应的文件对象
     * @throws IllegalStateException 资源未找到或 URL 转 File 失败时抛出，错误信息含操作指引
     */
    private static File findLingTestJar() {
        URL url = CleanSpringBootUnloadTest.class.getClassLoader()
                .getResource("lingframe-example-ling-test.jar");
        if (url == null) {
            throw new IllegalStateException(
                    "classpath 下未找到 lingframe-example-ling-test.jar，请将 jar 放到 src/test/resources/ 下");
        }
        try {
            return new File(url.toURI());
        } catch (Exception e) {
            throw new IllegalStateException(
                    "无法将 classpath 资源 lingframe-example-ling-test.jar 转为 File: " + url, e);
        }
    }

}