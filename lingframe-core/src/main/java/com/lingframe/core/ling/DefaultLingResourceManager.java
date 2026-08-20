package com.lingframe.core.ling;

import com.lingframe.core.event.EventBus;
import com.lingframe.core.event.InstanceDestroyedEvent;
import com.lingframe.api.event.LingEventListener;
import lombok.extern.slf4j.Slf4j;

import java.beans.Introspector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
public class DefaultLingResourceManager implements LingResourceManager, LingEventListener<InstanceDestroyedEvent> {

    private static final String GLOBAL_LISTENER_ID = "__lingframe_core__";

    private final transient ConcurrentHashMap<String, ExecutorService> threadPools = new ConcurrentHashMap<>();
    private final transient InvokableMethodCache methodCache;
    private final transient LingRepository lingRepository;
    private final transient EventBus eventBus;

    // 孤儿 AutoCloseable 注册表：lingId → version → 有序资源列表（注册尾插，关闭逆序）。
    // 所有结构变更（注册/反注册/摘除）统一在 registryLock 临界区内，内外层用 HashMap 即可，
    // 无需 ConcurrentHashMap——结构安全已由锁保证，叠加 CHM 属两套并发策略并存的死重。
    private final transient Map<String, Map<String, List<AutoCloseable>>> closeableRegistry = new HashMap<>();

    // 注册表自有锁（全局粒度）。本类现状无锁；withLifecycleLock 是 LingLifecycleEngine 接口的
    // default 方法（非 private），但本类未实现该接口，取不到此方法——本锁是新增，不是复用。
    // 全局粒度依据：注册仅发生在容器初始化（批量写），关闭仅发生在版本/整 Ling 卸载（一次性读），
    // 低频访问下无争用之虞。
    private final transient Object registryLock = new Object();

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
        // 原有逻辑：回收线程池
        reclaimResources(lingId);

        // 兜底：锁内整体摘除，锁外逐版本逆序关闭孤儿资源（正常流程应为空表）
        Map<String, List<AutoCloseable>> versionMap;
        synchronized (registryLock) {
            versionMap = closeableRegistry.remove(lingId);
        }
        if (versionMap == null || versionMap.isEmpty()) {
            return;
        }
        for (Map.Entry<String, List<AutoCloseable>> entry : versionMap.entrySet()) {
            closeList(lingId, entry.getKey(), entry.getValue());
        }
    }

    @Override
    public void closeResources(String lingId, String version) {
        // 锁内摘除条目，锁外执行 close()：close() 可能长时间阻塞，持锁等待会阻塞所有灵元的注册/反注册。
        List<AutoCloseable> list;
        synchronized (registryLock) {
            Map<String, List<AutoCloseable>> versionMap = closeableRegistry.get(lingId);
            if (versionMap == null) {
                return;
            }
            list = versionMap.remove(version);
            if (versionMap.isEmpty()) {
                closeableRegistry.remove(lingId);
            }
        }
        closeList(lingId, version, list);
        // 边界：close() 执行期间同版本迟到 register 会在锁内新建条目并留存，
        // 由整 Ling 卸载的 closeResources(lingId) 兜底释放——有界留存，不丢失。
        // P0 并发单测需覆盖此用例。
    }

    @Override
    public void registerCloseable(String lingId, String version, AutoCloseable closeable) {
        if (lingId == null || version == null || closeable == null) {
            // 注册入口是 DefaultLingContext（lingId/version 构造时已快照），正常路径不可能为空；
            // warn 而非静默返回，避免调用方 bug 被吞。
            log.warn("registerCloseable rejected: lingId/version/closeable must not be null");
            return;
        }
        synchronized (registryLock) {
            List<AutoCloseable> list = closeableRegistry
                    .computeIfAbsent(lingId, k -> new HashMap<>())
                    .computeIfAbsent(version, k -> new ArrayList<>());
            // 同一实例重复注册去重，避免框架重复关闭
            if (!list.contains(closeable)) {
                list.add(closeable);
            }
        }
    }

    @Override
    public void unregisterCloseable(String lingId, String version, AutoCloseable closeable) {
        if (lingId == null || version == null || closeable == null) {
            return;
        }
        synchronized (registryLock) {
            Map<String, List<AutoCloseable>> versionMap = closeableRegistry.get(lingId);
            if (versionMap == null) {
                return;
            }
            List<AutoCloseable> list = versionMap.get(version);
            if (list != null) {
                list.remove(closeable);
                if (list.isEmpty()) {
                    versionMap.remove(version);
                }
            }
            // 该 lingId 下无任何版本残留时，清理外层 map entry
            if (versionMap.isEmpty()) {
                closeableRegistry.remove(lingId);
            }
        }
    }

    private void closeList(String lingId, String version, List<AutoCloseable> list) {
        if (list == null || list.isEmpty()) {
            return;
        }
        int closed = 0, failed = 0;
        for (int i = list.size() - 1; i >= 0; i--) {   // 逆注册序
            AutoCloseable c = list.get(i);
            try {
                c.close();
                closed++;
            } catch (Exception e) {
                failed++;
                log.error("[ling:{}:{}] Failed to close orphan resource {} (continuing)",
                        lingId, version, c.getClass().getName(), e);
            }
        }
        log.info("[ling:{}:{}] Orphan cleanup: closed={}, failed={}", lingId, version, closed, failed);
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
