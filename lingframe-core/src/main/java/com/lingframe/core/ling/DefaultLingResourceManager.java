package com.lingframe.core.ling;

import com.lingframe.core.event.EventBus;
import com.lingframe.core.event.InstanceDestroyedEvent;
import com.lingframe.api.event.LingEventListener;
import lombok.extern.slf4j.Slf4j;

import java.beans.Introspector;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
public class DefaultLingResourceManager implements LingResourceManager, LingEventListener<InstanceDestroyedEvent> {

    private static final String GLOBAL_LISTENER_ID = "__lingframe_core__";

    private final ConcurrentHashMap<String, ExecutorService> threadPools = new ConcurrentHashMap<>();
    private final InvokableMethodCache methodCache;
    private final LingRepository lingRepository;
    private final EventBus eventBus;

    public DefaultLingResourceManager(EventBus globalEventBus, InvokableMethodCache methodCache) {
        this(null, globalEventBus, methodCache);
    }

    public DefaultLingResourceManager(LingRepository lingRepository, EventBus globalEventBus, InvokableMethodCache methodCache) {
        this.lingRepository = lingRepository;
        this.methodCache = methodCache;
        this.eventBus = globalEventBus;
        if (globalEventBus != null) {
            globalEventBus.subscribe(GLOBAL_LISTENER_ID, InstanceDestroyedEvent.class, this);
            log.info("DefaultLingResourceManager subscribed to InstanceDestroyedEvent");
        }
    }

    public ExecutorService allocateThreadPool(String lingId, int size) {
        return threadPools.computeIfAbsent(lingId, id -> Executors.newFixedThreadPool(size));
    }

    @Override
    public void onEvent(InstanceDestroyedEvent event) {
        log.info("Received InstanceDestroyedEvent for {}, version: {}. Reclaiming resources.", event.getLingId(),
                event.getVersion());
        // 线程池按 lingId 维度共享：仅当该灵元不再存在任何实例时才回收，避免多版本并存时误伤。
        if (shouldReclaimThreadPool(event.getLingId())) {
            reclaimResources(event.getLingId());
        }

        // 泄漏闭环：驱逐方法句柄缓存，释放目标 ClassLoader 强引用
        if (methodCache != null) {
            String prefix = event.getLingId() + ":" + event.getVersion() + "@";
            int evicted = methodCache.evictByPrefix(prefix);
            log.debug("Evicted {} cached MethodHandles for prefix {}", evicted, prefix);
        }
    }

    private boolean shouldReclaimThreadPool(String lingId) {
        if (lingRepository == null || lingId == null) {
            return true;
        }
        try {
            LingRuntime runtime = lingRepository.getRuntime(lingId);
            if (runtime == null || runtime.getInstancePool() == null) {
                return true;
            }
            return runtime.getInstancePool().getAllInstances().isEmpty();
        } catch (Exception ignored) {
            // 避免资源回收链路被旁路故障阻断
            return true;
        }
    }

    public void reclaimResources(String lingId) {
        ExecutorService pool = threadPools.remove(lingId);
        if (pool != null) {
            pool.shutdown();
            log.info("Thread pool for {} shutdown successfully", lingId);
        }
    }

    @Override
    public void cleanupCaches(String lingId, ClassLoader classLoader) {
        log.info("Cleaning up caches for ling: {}", lingId);
        if (classLoader == null)
            return;

        try {
            Introspector.flushCaches();
        } catch (Exception e) {
            log.debug("Failed to flush Introspector caches for {}: {}", lingId, e.getMessage());
        }
    }

    @Override
    public void closeResources(String lingId) {
        log.info("Closing resources for ling: {}", lingId);
        reclaimResources(lingId);
    }

    public void shutdown() {
        if (eventBus != null) {
            eventBus.unsubscribeAll(GLOBAL_LISTENER_ID);
        }
        threadPools.forEach((lingId, pool) -> {
            if (pool != null) {
                pool.shutdown();
            }
        });
        threadPools.clear();
    }
}
