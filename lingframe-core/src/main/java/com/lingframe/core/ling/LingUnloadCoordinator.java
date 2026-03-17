package com.lingframe.core.ling;

import com.lingframe.core.pipeline.InvocationPipelineEngine;
import com.lingframe.core.spi.LeakDetector;
import com.lingframe.core.spi.ResourceGuard;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * 统一编排卸载后置动作，避免生命周期引擎膨胀为“上帝类”。
 */
@Slf4j
@RequiredArgsConstructor
public class LingUnloadCoordinator {

    private final InvocationPipelineEngine pipelineEngine;
    private final List<ResourceGuard> resourceGuards;
    private final LingResourceManager lingResourceManager;
    private final LeakDetector leakDetector;

    /**
     * 版本级卸载后的清理动作。
     */
    public void onVersionUnload(String lingId, String version, ClassLoader classLoader) {
        cleanupWithGuards(lingId, version, classLoader);
    }

    /**
     * 整 Ling 卸载后的清理动作。
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
        cleanupWithGuards("fault-cleanup", null, classLoader);
    }

    /**
     * 版本卸载后的泄漏检测。
     */
    public void detectLeak(String lingId, String version, ClassLoader classLoader) {
        if (classLoader == null || leakDetector == null) {
            return;
        }
        String leakId = version != null ? lingId + ":" + version : lingId;
        try {
            leakDetector.detectLeak(leakId, classLoader);
        } catch (Exception e) {
            log.error("[{}] Leak detection failed for version {} with detector: {}", lingId, version,
                    leakDetector.getClass().getName(), e);
        }
    }

    /**
     * 整 Ling 卸载后的泄漏检测。
     */
    public void detectLeak(String lingId, ClassLoader classLoader) {
        if (classLoader == null || leakDetector == null) {
            return;
        }
        try {
            leakDetector.detectLeak(lingId, classLoader);
        } catch (Exception e) {
            log.error("[{}] Leak detection failed with detector: {}", lingId, leakDetector.getClass().getName(), e);
        }
    }

    private void cleanupWithGuards(String lingId, String version, ClassLoader classLoader) {
        if (classLoader == null) {
            return;
        }
        if (resourceGuards != null) {
            for (ResourceGuard guard : resourceGuards) {
                try {
                    guard.cleanup(lingId, classLoader);
                } catch (Exception e) {
                    String suffix = version == null ? "" : " for version " + version;
                    log.error("[{}] Resource cleanup failed{} with guard: {}", lingId, suffix,
                            guard.getClass().getName(), e);
                }
            }
        }
        if (lingResourceManager != null) {
            lingResourceManager.cleanupCaches(lingId, classLoader);
        }
    }
}
