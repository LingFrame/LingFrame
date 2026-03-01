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

    public DefaultLingResourceManager(EventBus globalEventBus, InvokableMethodCache methodCache) {
        this.methodCache = methodCache;
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
        reclaimResources(event.getLingId());

        // 泄漏闭环：驱逐方法句柄缓存，释放目标 ClassLoader 强引用
        if (methodCache != null) {
            String prefix = event.getLingId() + ":" + event.getVersion() + "@";
            int evicted = methodCache.evictByPrefix(prefix);
            log.debug("Evicted {} cached MethodHandles for prefix {}", evicted, prefix);
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
}
