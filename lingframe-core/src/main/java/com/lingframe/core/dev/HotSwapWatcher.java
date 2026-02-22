package com.lingframe.core.dev;

import com.lingframe.api.config.LingDefinition;
import com.lingframe.api.event.LingEventListener;
import com.lingframe.api.event.lifecycle.LingUninstalledEvent;
import com.lingframe.core.event.EventBus;
import com.lingframe.core.ling.LingManager;
import com.lingframe.core.exception.LingInstallException;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 热加载监听器
 * 职责：监听 target/classes 目录变化，触发单元重载
 */
@Slf4j
public class HotSwapWatcher implements LingEventListener<LingUninstalledEvent> {

    private final LingManager lingManager;
    private final EventBus eventBus;
    private WatchService watchService;
    // 核心映射：WatchKey -> LingId
    // 因为是递归监听，一个 LingId 会对应多个 WatchKey (每个子目录一个)
    private final Map<WatchKey, String> keyLingMap = new ConcurrentHashMap<>();

    // 源码映射：LingId -> ClassesDir (用于重装)
    private final Map<String, File> lingSourceMap = new ConcurrentHashMap<>();
    private final Map<String, LingDefinition> lingDefinitionMap = new ConcurrentHashMap<>();

    // 重载保护集合：记录当前正在进行热重载的单元ID
    // 防止在重载过程中(先uninstall再install)误触发资源回收逻辑
    private final Set<String> reloadingLings = ConcurrentHashMap.newKeySet();

    private final AtomicBoolean isStarted = new AtomicBoolean(false);

    // 防抖调度器：防止一次保存触发多次重载
    private final ScheduledExecutorService debounceExecutor = Executors.newSingleThreadScheduledExecutor(
            r -> {
                Thread thread = new Thread(r, "lingframe-hotswap-debounce");
                thread.setDaemon(true);
                thread.setUncaughtExceptionHandler(
                        (t, e) -> log.error("Thread pool thread {} exception: {}", t.getName(), e.getMessage()));
                return thread;
            });
    private ScheduledFuture<?> debounceTask;

    public HotSwapWatcher(LingManager lingManager, EventBus eventBus) {
        this.lingManager = lingManager;
        this.eventBus = eventBus;
        // 注册自己监听卸载事件
        this.eventBus.subscribe("lingframe-hotswap", LingUninstalledEvent.class, this);
    }

    /**
     * 初始化监听服务 (Lazy Init)
     */
    private synchronized void ensureInit() {
        if (isStarted.get())
            return;
        try {
            this.watchService = FileSystems.getDefault().newWatchService();
            startWatchLoop();
            isStarted.set(true);
            log.info("[HotSwap] WatchService initialized (Lazy).");
        } catch (IOException e) {
            throw new LingInstallException("hotswap", "Failed to init WatchService", e);
        }
    }

    /**
     * 响应系统卸载事件 (自动清理资源)
     */
    @Override
    public void onEvent(LingUninstalledEvent event) {
        String lingId = event.getLingId();

        // [Critical] 如果是热重载导致的卸载，不要注销监听！
        if (reloadingLings.contains(lingId)) {
            log.debug("[HotSwap] Ignoring uninstall event for reloading ling: {}", lingId);
            return;
        }

        // 只有用户手动卸载(API)时，才真正停止监听
        unregister(lingId);
    }

    /**
     * 注册监听目录
     */
    public void register(String lingId, File classesDir) {
        ensureInit(); // 触发懒加载
        try {
            Path path = classesDir.toPath();
            // 递归注册所有子目录
            WatchKey key = path.register(watchService, StandardWatchEventKinds.ENTRY_MODIFY,
                    StandardWatchEventKinds.ENTRY_CREATE);
            keyLingMap.put(key, lingId);

            // 简单遍历一级子目录注册
            Files.walk(path, 10)
                    .filter(Files::isDirectory)
                    .forEach(p -> {
                        try {
                            WatchKey k = p.register(watchService, StandardWatchEventKinds.ENTRY_MODIFY);
                            keyLingMap.put(k, lingId);
                        } catch (IOException e) {
                            log.warn("Failed to watch subdir: {}", p, e);
                        }
                    });

            log.info("[HotSwap] Watching directory: {}", classesDir.getAbsolutePath());
        } catch (Exception e) {
            log.warn("Failed to watch dir: {}", classesDir, e);
        }
    }

    /**
     * 注销监听
     * 遍历 Map，移除该单元名下的所有 Key (O(N) 复杂度，但在卸载时可接受)
     */
    public void unregister(String lingId) {
        if (!isStarted.get())
            return;

        log.info("[HotSwap] Unregistering watcher for: {}", lingId);

        // 使用迭代器安全删除
        Iterator<Map.Entry<WatchKey, String>> it = keyLingMap.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<WatchKey, String> entry = it.next();
            if (entry.getValue().equals(lingId)) {
                WatchKey key = entry.getKey();
                try {
                    key.cancel(); // 释放操作系统资源
                } catch (Exception ignored) {
                }
                it.remove(); // 移除 Map 条目
            }
        }
    }

    // 关闭服务 (App shutdown 时调用)
    public synchronized void shutdown() {
        try {
            if (watchService != null)
                watchService.close();
            debounceExecutor.shutdownNow();
        } catch (IOException e) {
            // ignore
        }
    }

    private void startWatchLoop() {
        Thread thread = new Thread(() -> {
            while (true) {
                try {
                    if (watchService == null)
                        break;

                    WatchKey key = watchService.take();

                    String lingId = keyLingMap.get(key);
                    if (lingId != null) {
                        // 触发防抖重载
                        scheduleReload(lingId);
                    }

                    // 清空事件队列，防止死循环
                    key.pollEvents();

                    // 重置 key，如果重置失败说明目录已不可访问
                    if (!key.reset()) {
                        keyLingMap.remove(key);
                    }
                } catch (InterruptedException e) {
                    break;
                } catch (ClosedWatchServiceException e) {
                    break; // 服务关闭，退出
                } catch (Exception e) {
                    log.error("Error in HotSwap loop", e);
                }
            }
        });
        thread.setDaemon(true);
        thread.setName("lingframe-hotswap-watcher");
        thread.setUncaughtExceptionHandler(
                (t, e) -> log.error("Thread pool thread {} exception: {}", t.getName(), e.getMessage()));
        thread.start();
    }

    private synchronized void scheduleReload(String lingId) {
        if (debounceTask != null && !debounceTask.isDone()) {
            debounceTask.cancel(false);
        }
        // 延迟 1000ms 执行，等待 IDE 编译完成
        debounceTask = debounceExecutor.schedule(() -> {
            log.info("=================================================");
            log.info("⚡ Source change detected, hot reloading ling: {}", lingId);

            // 检查是否存在编译错误文件
            if (hasCompilationErrors(lingId)) {
                log.warn("Compilation error detected, skipping hot reload: {}", lingId);
                log.info("=================================================");
                return;
            }

            File source = lingSourceMap.get(lingId);
            if (source == null) {
                log.error("Source lost for ling: {}", lingId);
                return;
            }

            LingDefinition lingDefinition = lingDefinitionMap.get(lingId);
            if (lingDefinition == null) {
                log.warn("LingDefinition lost for ling: {}", lingId);
                return;
            }

            try {
                // 标记正在重载 (保护 WatchKey 不被回收)
                reloadingLings.add(lingId);

                // 卸载旧版
                lingManager.uninstall(lingId);

                // 安装新版 (Dev模式)
                lingManager.installDev(lingDefinition, source);

                log.info("⚡ Hot swap completed: {}", lingId);

            } catch (Exception e) {
                log.error("Hot swap failed", e);
            } finally {
                // 解除保护
                reloadingLings.remove(lingId);
            }
            log.info("=================================================");
        }, 1000, TimeUnit.MILLISECONDS);
    }

    /**
     * 检查是否存在编译错误
     *
     * @param lingId 单元ID
     * @return 是否存在编译错误
     */
    private boolean hasCompilationErrors(String lingId) {
        // 简单实现：检查是否存在 .class 文件
        // 更完善的实现应该检查编译器输出或错误日志
        for (Map.Entry<WatchKey, String> entry : keyLingMap.entrySet()) {
            if (entry.getValue().equals(lingId)) {
                Path dir = (Path) entry.getKey().watchable();
                try {
                    // 检查目录中是否存在 .class 文件
                    return Files.walk(dir)
                            .noneMatch(path -> path.toString().endsWith(".class"));
                } catch (IOException e) {
                    log.warn("Failed to check compilation status: {}", dir, e);
                }
            }
        }
        return false;
    }
}