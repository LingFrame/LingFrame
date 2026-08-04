package com.lingframe.core.ling;

import com.lingframe.core.pipeline.InvocationPipelineEngine;
import com.lingframe.core.spi.LeakDetector;
import com.lingframe.core.spi.LeakRiskReport;
import com.lingframe.core.spi.LingUnloadHook;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 统一编排卸载后置动作。
 * <p>
 * 清理分两阶段串行执行，同阶段内并行执行：
 * <ol>
 *   <li>生态阶段（ecosystemHooks）：清理外部生态框架的静态缓存，仅注册了生态 Hook 的场景执行</li>
 *   <li>JVM 阶段（jvmHooks）：清理 JDBC 驱动、线程引用、ShutdownHook 等 JVM 级残留，永远执行</li>
 * </ol>
 * <p>
 * 并行执行使用小线程池（4 线程，核心线程空闲超时回收），在 {@link #shutdown()} 时关闭。
 */
@Slf4j
public class LingUnloadCoordinator {

    private static final int PARALLELISM = 4;
    private static final long HOOK_TIMEOUT_SECONDS = 30;

    private final InvocationPipelineEngine pipelineEngine;
    private final List<LingUnloadHook> ecosystemHooks;
    private final List<LingUnloadHook> jvmHooks;
    private final LingResourceManager lingResourceManager;
    private final LeakDetector leakDetector;
    private final ExecutorService parallelExecutor;

    /**
     * 全参数构造（两桶模式）。
     *
     * @param pipelineEngine  流水线引擎
     * @param ecosystemHooks  生态阶段 Hook（可为 null 或空）
     * @param jvmHooks        JVM 阶段 Hook（可为 null 或空）
     * @param lingResourceManager 资源管理器
     * @param leakDetector    泄漏检测器
     */
    public LingUnloadCoordinator(InvocationPipelineEngine pipelineEngine,
                                 List<LingUnloadHook> ecosystemHooks,
                                 List<LingUnloadHook> jvmHooks,
                                 LingResourceManager lingResourceManager,
                                 LeakDetector leakDetector) {
        this.pipelineEngine = pipelineEngine;
        this.ecosystemHooks = ecosystemHooks != null ? ecosystemHooks : Collections.emptyList();
        this.jvmHooks = jvmHooks != null ? jvmHooks : Collections.emptyList();
        this.lingResourceManager = lingResourceManager;
        this.leakDetector = leakDetector;
        // 自定义 ThreadPoolExecutor：开启核心线程超时回收，
        // 避免 ling-unload-worker 线程常驻导致 lambda 捕获的 ClassLoader 引用残留在栈槽中
        // （DEV 模式主动 GC 会扫描栈槽残留，导致 LingClassLoader 无法回收）
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                PARALLELISM, PARALLELISM,
                1L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<Runnable>(),
                r -> {
                    Thread t = new Thread(r, "ling-unload-worker");
                    t.setDaemon(true);
                    return t;
                });
        executor.allowCoreThreadTimeOut(true);
        this.parallelExecutor = executor;
    }

    public LeakDetector getLeakDetector() {
        return leakDetector;
    }

    /**
     * 版本级卸载后的清理动作。
     * <p>
     * 灵核的守卫已收口到
     * {@link DefaultLingLifecycleEngine#undeployWithReport} 入口
     * (灵核不是 LingRuntime，getRoutableTarget 返回非 null 的 LingCoreRoutableTarget)，
     * 本方法不做重复判断——这是 claude 方案"收口到唯一入口"的检验标准:
     * 如果删掉这里仍安全,说明收口成功。
     */
    public void onVersionUnload(String lingId, String version, ClassLoader classLoader) {
        cleanupWithHooks(lingId, version, classLoader);
        // 同步驱逐该版本的方法句柄缓存，避免依赖 InstanceDestroyedEvent 异步触发
        // 异步事件到达前若 MethodHandle 仍持有目标 Class 的强引用，会推迟 ClassLoader 回收
        if (pipelineEngine != null && version != null) {
            int evicted = pipelineEngine.evictMethodCacheByPrefix(lingId + ":" + version + "@");
            if (evicted > 0) {
                log.info("[{}] Evicted {} method handles for version {}", lingId, evicted, version);
            }
        }
    }

    /**
     * 整 Ling 卸载后的清理动作。
     * <p>
     * 灵核的守卫已收口到
     * {@link DefaultLingLifecycleEngine#undeployWithReport} 入口,
     * 本方法不做重复判断。
     */
    public void onLingUnload(String lingId) {
        if (pipelineEngine != null) {
            pipelineEngine.evictLingResources(lingId);
            int evictedHandles = pipelineEngine.evictMethodCache(lingId);
            if (evictedHandles > 0) {
                log.info("[{}] Evicted {} method handles after unload", lingId, evictedHandles);
            }
        }
        if (lingResourceManager != null) {
            lingResourceManager.closeResources(lingId);
        }
    }

    /**
     * 安装失败回滚清理。
     */
    public void onFailureCleanup(ClassLoader classLoader) {
        cleanupWithHooks("fault-cleanup", null, classLoader);
    }

    public LeakRiskReport checkBeforeVersionUnload(String lingId, String version, ClassLoader classLoader) {
        if (classLoader == null) {
            return LeakRiskReport.checkFailed(
                    lingId,
                    version,
                    "Target ClassLoader is unavailable before unload",
                    null,
                    leakDetector == null ? getClass().getName() : leakDetector.getClass().getName());
        }
        if (leakDetector == null) {
            return LeakRiskReport.checkFailed(
                    lingId,
                    version,
                    "Leak precheck is unavailable because no LeakDetector is configured",
                    null,
                    getClass().getName());
        }
        try {
            return leakDetector.checkBefore(lingId, version, classLoader);
        } catch (Exception e) {
            log.error("[{}] Leak precheck failed for version {} with detector: {}", lingId, version,
                    leakDetector.getClass().getName(), e);
            return LeakRiskReport.checkFailed(
                    lingId,
                    version,
                    "Leak precheck failed: " + e.getMessage(),
                    Collections.singletonList(e.getClass().getName()),
                    leakDetector.getClass().getName());
        }
    }

    public List<LeakRiskReport> checkBeforeLingUnload(String lingId, List<LingInstance> instances) {
        if (instances == null || instances.isEmpty()) {
            return Collections.emptyList();
        }
        List<LeakRiskReport> reports = new ArrayList<>();
        for (LingInstance instance : instances) {
            if (instance == null) {
                continue;
            }
            reports.add(checkBeforeVersionUnload(lingId, instance.getVersion(), instance.getClassLoader()));
        }
        return reports;
    }

    /**
     * 版本卸载后的泄漏检测。
     */
    public void detectLeak(String lingId, String version, ClassLoader classLoader) {
        if (classLoader == null || leakDetector == null) {
            return;
        }
        try {
            leakDetector.detectLeak(lingId, version, classLoader);
        } catch (Exception e) {
            log.error("[{}] Leak detection failed for version {} with detector: {}", lingId, version,
                    leakDetector.getClass().getName(), e);
        }
    }

    /**
     * 关闭协调器持有的常驻线程池，并调用所有 Hook 的 shutdown。
     */
    public void shutdown() {
        // 关闭线程池
        parallelExecutor.shutdown();
        try {
            if (!parallelExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                parallelExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            parallelExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        // 通知所有 Hook 释放自身资源
        shutdownHooks(ecosystemHooks);
        shutdownHooks(jvmHooks);
    }

    // =========================================================================
    // 内部实现
    // =========================================================================

    private void cleanupWithHooks(String lingId, String version, ClassLoader classLoader) {
        if (classLoader == null) {
            return;
        }

        // 阶段 1：生态清理（桶内并行）
        if (!ecosystemHooks.isEmpty()) {
            log.debug("[{}] Starting ecosystem cleanup phase ({} hooks)", lingId, ecosystemHooks.size());
            runHooksInParallel(ecosystemHooks, lingId, version, classLoader, "ecosystem");
        }

        // 阶段 2：JVM 清理（桶内并行）
        if (!jvmHooks.isEmpty()) {
            log.debug("[{}] Starting JVM cleanup phase ({} hooks)", lingId, jvmHooks.size());
            runHooksInParallel(jvmHooks, lingId, version, classLoader, "jvm");
        }

        // 资源管理器缓存清理
        if (lingResourceManager != null) {
            lingResourceManager.cleanupCaches(lingId, classLoader);
        }
    }

    /**
     * 桶内并行执行所有 Hook，单个 Hook 异常不阻塞其他 Hook。
     */
    private void runHooksInParallel(List<LingUnloadHook> hooks, String lingId, String version,
                                    ClassLoader classLoader, String phase) {
        // 单 Hook 无需并行开销
        if (hooks.size() == 1) {
            invokeHook(hooks.get(0), lingId, version, classLoader, phase);
            return;
        }

        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());
        try {
            parallelExecutor.invokeAll(
                    hooks.stream()
                            .map(hook -> (Callable<Void>) () -> {
                                try {
                                    invokeHook(hook, lingId, version, classLoader, phase);
                                } catch (Throwable t) {
                                    // 捕获 Throwable 而非 Exception，防止 Error（如 NoClassDefFoundError、
                                    // StackOverflowError）被 invokeAll 静默吞进 Future，导致日志突然消失
                                    errors.add(t);
                                }
                                return null;
                            })
                            .collect(Collectors.toList()),
                    HOOK_TIMEOUT_SECONDS,
                    TimeUnit.SECONDS
            );
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[{}] Interrupted during {} cleanup phase", lingId, phase);
        }

        if (!errors.isEmpty()) {
            log.warn("[{}] {} cleanup phase completed with {} error(s)", lingId, phase, errors.size());
            // Error 代表 JVM 级致命问题（OOM / StackOverflow / LinkageError），
            // 不应被静默吞掉：找出第一个 Error 向上抛出，让调用方感知严重故障。
            for (Throwable t : errors) {
                if (t instanceof Error) {
                    throw (Error) t;
                }
            }
        }
    }

    private void invokeHook(LingUnloadHook hook, String lingId, String version,
                           ClassLoader classLoader, String phase) {
        try {
            hook.cleanup(lingId, classLoader);
        } catch (Error e) {
            // JVM 致命错误不吞：记录后向上抛出，由 runHooksInParallel 统一收集并重抛
            String suffix = version == null ? "" : " for version " + version;
            log.error("[{}] {} cleanup threw Error{} with hook: {}, aborting",
                    lingId, phase, suffix, hook.getClass().getName(), e);
            throw e;
        } catch (Throwable t) {
            // 普通异常隔离：单个 Hook 失败不阻塞同桶其他 Hook
            String suffix = version == null ? "" : " for version " + version;
            log.error("[{}] {} cleanup failed{} with hook: {}", lingId, phase, suffix,
                    hook.getClass().getName(), t);
            if (t instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static void shutdownHooks(List<LingUnloadHook> hooks) {
        for (LingUnloadHook hook : hooks) {
            try {
                hook.shutdown();
            } catch (Exception e) {
                // shutdown 异常不阻塞其他 Hook
            }
        }
    }
}
