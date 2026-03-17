package com.lingframe.core.dev;

import com.lingframe.api.config.LingDefinition;
import com.lingframe.api.event.LingEventListener;
import com.lingframe.api.event.lifecycle.LingUninstalledEvent;
import com.lingframe.core.event.EventBus;
import com.lingframe.core.exception.LingInstallException;
import com.lingframe.core.ling.LingLifecycleEngine;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.nio.file.*;
import java.util.Collections;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

@Slf4j
public class HotSwapWatcher implements LingEventListener<LingUninstalledEvent> {

    private final LingLifecycleEngine lifecycleEngine;
    private final EventBus eventBus;
    private WatchService watchService;

    private final Map<WatchKey, String> keyLingMap = new ConcurrentHashMap<>();
    private final Map<String, File> lingSourceMap = new ConcurrentHashMap<>();
    private final Map<String, LingDefinition> lingDefinitionMap = new ConcurrentHashMap<>();
    private final Set<String> reloadingLings = ConcurrentHashMap.newKeySet();

    // ✅ 新增：跟踪旧 ClassLoader 是否被 GC
    private final Map<String, WeakReference<ClassLoader>> oldClassLoaderRefs = new ConcurrentHashMap<>();

    private final AtomicBoolean isStarted = new AtomicBoolean(false);

    private final ScheduledExecutorService debounceExecutor = Executors.newSingleThreadScheduledExecutor(
            r -> {
                Thread thread = new Thread(r, "lingframe-hotswap-debounce");
                thread.setDaemon(true);
                thread.setUncaughtExceptionHandler(
                        (t, e) -> log.error("Thread {} exception: {}", t.getName(), e.getMessage()));
                return thread;
            });

    // ✅ 改为 volatile，防止并发问题
    private volatile ScheduledFuture<?> debounceTask;

    public HotSwapWatcher(LingLifecycleEngine lifecycleEngine, EventBus eventBus) {
        this.lifecycleEngine = lifecycleEngine;
        this.eventBus = eventBus;
        this.eventBus.subscribe("lingframe-hotswap", LingUninstalledEvent.class, this);
    }

    @Override
    public void onEvent(LingUninstalledEvent event) {
        String lingId = event.getLingId();
        if (reloadingLings.contains(lingId)) {
            log.debug("[HotSwap] Ignoring uninstall event for reloading ling: {}", lingId);
            return;
        }
        unregister(lingId);
    }

    private synchronized void scheduleReload(String lingId) {
        if (debounceTask != null && !debounceTask.isDone()) {
            debounceTask.cancel(false);
        }

        debounceTask = debounceExecutor.schedule(() -> {
            doReload(lingId);
            // ✅ 任务完成后清除引用，防止 lambda 被 ScheduledFuture 持有
            debounceTask = null;
        }, 1000, TimeUnit.MILLISECONDS);
    }

    /**
     * ✅ 抽取热加载核心逻辑，增加 TCCL 保护和 GC 验证
     */
    private void doReload(String lingId) {
        log.info("=================================================");
        log.info("[HotSwap] Source change detected, hot reloading: {}", lingId);

        if (hasCompilationErrors(lingId)) {
            log.warn("[HotSwap] Compilation error detected, skipping: {}", lingId);
            log.info("=================================================");
            return;
        }

        File source = lingSourceMap.get(lingId);
        if (source == null) {
            log.error("[HotSwap] Source lost for ling: {}", lingId);
            return;
        }

        LingDefinition lingDefinition = lingDefinitionMap.get(lingId);
        if (lingDefinition == null) {
            log.warn("[HotSwap] LingDefinition lost for ling: {}", lingId);
            return;
        }

        // ✅ 保护当前线程的 TCCL
        Thread currentThread = Thread.currentThread();
        ClassLoader originalTCCL = currentThread.getContextClassLoader();

        try {
            reloadingLings.add(lingId);

            // ✅ 在 undeploy 前，获取旧 ClassLoader 的弱引用用于验证
            ClassLoader oldClassLoader = getOldClassLoader(lingId);
            WeakReference<ClassLoader> weakRef = null;
            if (oldClassLoader != null) {
                weakRef = new WeakReference<>(oldClassLoader);
                oldClassLoader = null; // 立即释放强引用
            }

            // ========== 卸载旧版 ==========
            lifecycleEngine.undeploy(lingId);

            // ✅ 强制恢复 TCCL（防止 undeploy 过程中被改变）
            currentThread.setContextClassLoader(originalTCCL);

            // ========== 安装新版 ==========
            boolean isCanary = resolveCanaryFlag(lingDefinition);
            boolean isDefault = !isCanary;
            lifecycleEngine.deploy(lingDefinition, source, isDefault, Collections.emptyMap());

            // ✅ 再次恢复 TCCL（防止 deploy/start 过程中被改变）
            currentThread.setContextClassLoader(originalTCCL);

            log.info("[HotSwap] Hot swap completed: {}", lingId);

            // ✅ 验证旧 ClassLoader 是否可以被 GC
            if (weakRef != null) {
                verifyClassLoaderGC(lingId, weakRef);
            }

        } catch (Exception e) {
            log.error("[HotSwap] Hot swap failed for: {}", lingId, e);
            // ✅ 确保异常时也恢复 TCCL
            currentThread.setContextClassLoader(originalTCCL);
        } finally {
            reloadingLings.remove(lingId);
        }
        log.info("=================================================");
    }

    /**
     * ✅ 获取当前灵元的 ClassLoader（undeploy 前）
     */
    private ClassLoader getOldClassLoader(String lingId) {
        try {
            // 通过 lifecycleEngine 获取当前容器的 ClassLoader
            // 需要 lifecycleEngine 提供这个能力
            return lifecycleEngine.getClassLoader(lingId);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * ✅ 验证旧 ClassLoader 是否已被 GC
     */
    private void verifyClassLoaderGC(String lingId, WeakReference<ClassLoader> weakRef) {
        // 延迟验证，给 GC 时间
        debounceExecutor.schedule(() -> {
            System.gc();
            try {
                Thread.sleep(500);
            } catch (InterruptedException ignored) {
            }
            System.gc();

            if (weakRef.get() == null) {
                log.info("✅ [HotSwap] Old ClassLoader for [{}] has been GC'd", lingId);
            } else {
                log.warn("⚠️ [HotSwap] Old ClassLoader for [{}] is STILL ALIVE - memory leak!", lingId);
                // 可以在这里触发 heap dump 进行分析
            }
        }, 5, TimeUnit.SECONDS);
    }

    /**
     * ✅ 抽取 canary 判断逻辑
     */
    private boolean resolveCanaryFlag(LingDefinition lingDefinition) {
        Map<String, Object> properties = lingDefinition.getProperties();
        if (properties == null) return false;
        Object value = properties.get("canary");
        if (value instanceof Boolean) return (Boolean) value;
        if (value instanceof Number) return ((Number) value).intValue() != 0;
        if (value != null) return "true".equalsIgnoreCase(String.valueOf(value));
        return false;
    }

    // ======================== 注册/注销 ========================

    public void register(String lingId, File classesDir, LingDefinition definition) {
        if (lingId == null || classesDir == null || definition == null) {
            return;
        }
        lingSourceMap.put(lingId, classesDir);
        lingDefinitionMap.put(lingId, definition);
        register(lingId, classesDir);
    }

    public void register(String lingId, File classesDir) {
        ensureInit();
        try {
            cleanupWatchKeys(lingId);
            Path path = classesDir.toPath();
            WatchKey key = path.register(watchService,
                    StandardWatchEventKinds.ENTRY_MODIFY,
                    StandardWatchEventKinds.ENTRY_CREATE);
            keyLingMap.put(key, lingId);

            try (Stream<Path> paths = Files.walk(path, 10)) {
                paths.filter(Files::isDirectory).forEach(p -> {
                    try {
                        WatchKey k = p.register(watchService, StandardWatchEventKinds.ENTRY_MODIFY);
                        keyLingMap.put(k, lingId);
                    } catch (IOException e) {
                        log.warn("Failed to watch subdir: {}", p, e);
                    }
                });
            }
            log.info("[HotSwap] Watching directory: {}", classesDir.getAbsolutePath());
        } catch (Exception e) {
            log.warn("Failed to watch dir: {}", classesDir, e);
        }
    }

    public void unregister(String lingId) {
        if (!isStarted.get()) return;
        log.info("[HotSwap] Unregistering watcher for: {}", lingId);
        cleanupWatchKeys(lingId);
        lingSourceMap.remove(lingId);
        lingDefinitionMap.remove(lingId);
        oldClassLoaderRefs.remove(lingId);
    }

    private void cleanupWatchKeys(String lingId) {
        Iterator<Map.Entry<WatchKey, String>> it = keyLingMap.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<WatchKey, String> entry = it.next();
            if (entry.getValue().equals(lingId)) {
                try {
                    entry.getKey().cancel();
                } catch (Exception ignored) {
                }
                it.remove();
            }
        }
    }

    // ======================== WatchService ========================

    private synchronized void ensureInit() {
        if (isStarted.get()) return;
        try {
            this.watchService = FileSystems.getDefault().newWatchService();
            startWatchLoop();
            isStarted.set(true);
        } catch (IOException e) {
            throw new LingInstallException("hotswap", "Failed to init WatchService", e);
        }
    }

    private void startWatchLoop() {
        Thread thread = new Thread(() -> {
            while (true) {
                try {
                    if (watchService == null) break;
                    WatchKey key = watchService.take();
                    String lingId = keyLingMap.get(key);
                    if (lingId != null) {
                        scheduleReload(lingId);
                    }
                    key.pollEvents();
                    if (!key.reset()) {
                        keyLingMap.remove(key);
                    }
                } catch (InterruptedException | ClosedWatchServiceException e) {
                    break;
                } catch (Exception e) {
                    log.error("Error in HotSwap loop", e);
                }
            }
        });
        thread.setDaemon(true);
        thread.setName("lingframe-hotswap-watcher");
        thread.start();
    }

    private boolean hasCompilationErrors(String lingId) {
        for (Map.Entry<WatchKey, String> entry : keyLingMap.entrySet()) {
            if (entry.getValue().equals(lingId)) {
                Path dir = (Path) entry.getKey().watchable();
                try (Stream<Path> paths = Files.walk(dir)) {
                    return paths.noneMatch(path -> path.toString().endsWith(".class"));
                } catch (IOException e) {
                    log.warn("Failed to check compilation status: {}", dir, e);
                }
            }
        }
        return false;
    }

    public synchronized void shutdown() {
        try {
            if (watchService != null) watchService.close();
            debounceExecutor.shutdownNow();
            if (eventBus != null) eventBus.unsubscribeAll("lingframe-hotswap");
        } catch (IOException ignored) {
        }
    }
}