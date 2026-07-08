package com.lingframe.core.dev;

import com.lingframe.api.config.LingDefinition;
import com.lingframe.api.event.LingEventListener;
import com.lingframe.api.event.lifecycle.LingUninstalledEvent;
import com.lingframe.core.event.EventBus;
import com.lingframe.core.exception.LingInstallException;
import com.lingframe.core.ling.LingInstance;
import com.lingframe.core.ling.LingLifecycleEngine;
import com.lingframe.core.ling.LingRepository;
import com.lingframe.core.ling.LingRuntime;
import com.lingframe.core.spi.LeakDetector;
import com.lingframe.core.spi.LingHotSwapWatcher;
import com.lingframe.core.util.NamedThreadFactory;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

/**
 * 负责开发阶段的文件变动监听并自动触发 LingFrame 热重载。
 * <p>
 * KISS：本类仅负责“监听并触发”，泄漏检测等重型任务由专门的基础设施（LeakDetector）承接。
 */
@Slf4j
public class HotSwapWatcher implements LingEventListener<LingUninstalledEvent>, LingHotSwapWatcher {

    private volatile LingLifecycleEngine lifecycleEngine;
    private final LingRepository lingRepository;
    private final EventBus eventBus;
    private final LeakDetector leakDetector;
    private WatchService watchService;

    private final Map<WatchKey, String> keyLingMap = new ConcurrentHashMap<>();
    private final Map<String, File> lingSourceMap = new ConcurrentHashMap<>();
    private final Map<String, LingDefinition> lingDefinitionMap = new ConcurrentHashMap<>();
    private final Set<String> reloadingLings = ConcurrentHashMap.newKeySet();

    private final AtomicBoolean isStarted = new AtomicBoolean(false);

    private final ScheduledExecutorService debounceExecutor = Executors.newSingleThreadScheduledExecutor(
            NamedThreadFactory.daemon("lingframe-hotswap-debounce"));

    // 按 lingId 维护 debounce 任务，避免不同灵元的重载互相取消
    private final Map<String, ScheduledFuture<?>> debounceTasks = new ConcurrentHashMap<>();

    public HotSwapWatcher(LingLifecycleEngine lifecycleEngine,
            LingRepository lingRepository,
            EventBus eventBus,
            LeakDetector leakDetector) {
        this.lingRepository = lingRepository;
        this.eventBus = eventBus;
        this.leakDetector = leakDetector;
        this.lifecycleEngine = lifecycleEngine;
        this.eventBus.subscribe("lingframe-hotswap", LingUninstalledEvent.class, this);
    }

    /**
     * 延迟绑定生命周期引擎。
     * <p>
     * 用于解决 native 装配场景下 watcher 与 lifecycleEngine 的循环依赖：
     * watcher 必须在 Builder 构造 engine 前创建（作为 hotSwapWatcher 参数传入），
     * 但 watcher 又需要 engine 引用。此时先传 null 构造，engine 创建后调用此方法绑定。
     *
     * @param engine 生命周期引擎，不可为 null
     */
    public void setLifecycleEngine(LingLifecycleEngine engine) {
        this.lifecycleEngine = Objects.requireNonNull(engine, "lifecycleEngine is required");
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

    private void scheduleReload(String lingId) {
        // compute 保证对单个 lingId 的 cancel+schedule 原子性，无需 synchronized
        debounceTasks.compute(lingId, (k, existing) -> {
            if (existing != null && !existing.isDone()) {
                existing.cancel(false);
            }
            // holder 模式：让 lambda 内部能引用自己的 future，实现条件删除
            final ScheduledFuture<?>[] holder = new ScheduledFuture<?>[1];
            holder[0] = debounceExecutor.schedule(() -> {
                try {
                    doReload(lingId);
                } finally {
                    // 条件删除：仅当 map 中仍是自己的 future 时才移除，
                    // 避免误删 doReload 期间新调度的任务
                    debounceTasks.remove(lingId, holder[0]);
                }
            }, 1000, TimeUnit.MILLISECONDS);
            return holder[0];
        });
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

            // 1. 在卸载前记录旧的 ClassLoader 视图
            Map<String, ClassLoader> oldLoaders = snapshotClassLoaders(lingId);

            // 2. 执行卸载与重部署
            lifecycleEngine.undeploy(lingId);

            // ✅ 强制恢复 TCCL（防止 undeploy 过程中被改变）
            currentThread.setContextClassLoader(originalTCCL);

            boolean isCanary = resolveCanaryFlag(lingDefinition);
            lifecycleEngine.deploy(lingDefinition, source, !isCanary, Collections.emptyMap());
            // ✅ 再次恢复 TCCL（防止 deploy/start 过程中被改变）
            currentThread.setContextClassLoader(originalTCCL);

            log.info("[HotSwap] Hot swap completed: {}", lingId);

            // 3. 将旧版的 ClassLoader 提交给统一的检测器进行收尾验证（符合职责归位原则）
            if (leakDetector != null) {
                oldLoaders.forEach((version, loader) -> leakDetector.detectLeak(lingId, version, loader));
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

    private Map<String, ClassLoader> snapshotClassLoaders(String lingId) {
        if (lingRepository == null)
            return Collections.emptyMap();

        LingRuntime runtime = lingRepository.getRuntime(lingId);
        if (runtime == null)
            return Collections.emptyMap();

        Map<String, ClassLoader> loaders = new HashMap<>();
        for (LingInstance instance : runtime.getInstancePool().getAllInstances()) {
            if (instance.getClassLoader() != null) {
                loaders.put(instance.getVersion(), instance.getClassLoader());
            }
        }
        return loaders;
    }

    /**
     * ✅ 抽取 canary 判断逻辑
     */
    private boolean resolveCanaryFlag(LingDefinition lingDefinition) {
        Map<String, Object> properties = lingDefinition.getProperties();
        if (properties == null)
            return false;
        Object value = properties.get("canary");
        if (value instanceof Boolean)
            return (Boolean) value;
        return "true".equalsIgnoreCase(String.valueOf(value));
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
                    } catch (IOException ignored) {
                    }
                });
            }
            log.info("[HotSwap] Watching directory: {}", classesDir.getAbsolutePath());
        } catch (Exception e) {
            log.warn("Failed to watch dir: {}", classesDir, e);
        }
    }

    public void unregister(String lingId) {
        if (!isStarted.get())
            return;
        log.info("[HotSwap] Unregistering watcher for: {}", lingId);
        cleanupWatchKeys(lingId);
        lingSourceMap.remove(lingId);
        lingDefinitionMap.remove(lingId);
    }

    private void cleanupWatchKeys(String lingId) {
        Iterator<Map.Entry<WatchKey, String>> it = keyLingMap.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<WatchKey, String> entry = it.next();
            if (entry.getValue().equals(lingId)) {
                try {
                    entry.getKey().cancel();
                } catch (Exception ignored) {
                    log.trace("WatchKey cancel failed for ling {}: {}", lingId, ignored.getMessage());
                }
                it.remove();
            }
        }
    }

    // ======================== WatchService ========================

    private synchronized void ensureInit() {
        if (isStarted.get())
            return;
        try {
            this.watchService = FileSystems.getDefault().newWatchService();
            startWatchLoop();
            isStarted.set(true);
        } catch (IOException e) {
            throw new LingInstallException("hotswap", "Failed to init WatchService", e);
        }
    }

    private static final int MAX_CONSECUTIVE_ERRORS = 5;
    private static final long ERROR_RETRY_MILLIS = 1000;

    private void startWatchLoop() {
        Thread thread = new Thread(() -> {
            int consecutiveErrors = 0;
            while (true) {
                try {
                    if (watchService == null)
                        break;
                    WatchKey key = watchService.take();
                    consecutiveErrors = 0;
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
                    consecutiveErrors++;
                    if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
                        log.error("HotSwap watch loop exiting after {} consecutive errors", consecutiveErrors, e);
                        break;
                    }
                    log.warn("HotSwap watch loop error ({}/{}), retrying in {}ms",
                            consecutiveErrors, MAX_CONSECUTIVE_ERRORS, ERROR_RETRY_MILLIS, e);
                    try {
                        Thread.sleep(ERROR_RETRY_MILLIS);
                    } catch (InterruptedException ie) {
                        break;
                    }
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
                    return paths.noneMatch(p -> p.toString().endsWith(".class"));
                } catch (IOException ignored) {
                }
            }
        }
        return false;
    }

    public synchronized void shutdown() {
        try {
            if (watchService != null)
                watchService.close();
            // 取消所有待执行的 debounce 任务
            debounceTasks.values().forEach(task -> task.cancel(false));
            debounceTasks.clear();
            debounceExecutor.shutdownNow();
            if (eventBus != null)
                eventBus.unsubscribeAll("lingframe-hotswap");
        } catch (IOException ignored) {
        }
    }
}
