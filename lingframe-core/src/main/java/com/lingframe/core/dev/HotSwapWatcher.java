package com.lingframe.core.dev;

import com.lingframe.core.plugin.PluginManager;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.util.Map;
import java.util.concurrent.*;

/**
 * 热加载监听器
 * 职责：监听 target/classes 目录变化，触发插件重载
 */
@Slf4j
public class HotSwapWatcher {

    private final WatchService watchService;
    private final PluginManager pluginManager;
    private final Map<WatchKey, String> keyPluginMap = new ConcurrentHashMap<>();

    // 防抖调度器：防止一次保存触发多次重载
    private final ScheduledExecutorService debounceExecutor = Executors.newSingleThreadScheduledExecutor(
            r -> {
                Thread t = new Thread(r, "lingframe-hotswap-debounce");
                t.setDaemon(true);
                return t;
            }
    );
    private ScheduledFuture<?> debounceTask;

    public HotSwapWatcher(PluginManager pluginManager) {
        this.pluginManager = pluginManager;
        try {
            this.watchService = FileSystems.getDefault().newWatchService();
            startWatchLoop();
        } catch (Exception e) {
            throw new RuntimeException("Failed to init HotSwapWatcher", e);
        }
    }

    /**
     * 注册监听目录
     */
    public void register(String pluginId, File classesDir) {
        try {
            Path path = classesDir.toPath();
            // 递归注册所有子目录（简化版仅演示根目录和一级子目录，生产环境需递归）
            WatchKey key = path.register(watchService, StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_CREATE);
            keyPluginMap.put(key, pluginId);

            // 简单遍历一级子目录注册 (实际需完整递归)
            Files.walk(path, 5)
                    .filter(Files::isDirectory)
                    .forEach(p -> {
                        try {
                            WatchKey k = p.register(watchService, StandardWatchEventKinds.ENTRY_MODIFY);
                            keyPluginMap.put(k, pluginId);
                        } catch (IOException e) {
                            log.warn("Failed to watch subdir: {}", p, e);
                        }
                    });

            log.info("[HotSwap] Watching directory: {}", classesDir.getAbsolutePath());
        } catch (Exception e) {
            log.warn("Failed to watch dir: {}", classesDir, e);
        }
    }

    private void startWatchLoop() {
        Thread t = new Thread(() -> {
            while (true) {
                try {
                    WatchKey key = watchService.take();
                    String pluginId = keyPluginMap.get(key);

                    if (pluginId != null) {
                        // 触发防抖重载
                        scheduleReload(pluginId);
                    }

                    // 重置 key，如果重置失败说明目录已不可访问
                    if (!key.reset()) {
                        keyPluginMap.remove(key);
                    }
                } catch (InterruptedException e) {
                    break;
                } catch (Exception e) {
                    log.error("Error in HotSwap loop", e);
                }
            }
        });
        t.setDaemon(true);
        t.setName("lingframe-hotswap-watcher");
        t.start();
    }

    private synchronized void scheduleReload(String pluginId) {
        if (debounceTask != null && !debounceTask.isDone()) {
            debounceTask.cancel(false);
        }
        // 延迟 1000ms 执行，等待 IDE 编译完成
        debounceTask = debounceExecutor.schedule(() -> {
            log.info("=================================================");
            log.info("⚡ 检测到源码变更，正在热重载插件: {}", pluginId);

            // 检查是否存在编译错误文件
            if (hasCompilationErrors(pluginId)) {
                log.warn("检测到编译错误，跳过热重载: {}", pluginId);
                log.info("=================================================");
                return;
            }

            try {
                pluginManager.reload(pluginId);
            } catch (Exception e) {
                log.error("Hot reload failed", e);
            }
            log.info("=================================================");
        }, 1000, TimeUnit.MILLISECONDS);
    }

    /**
     * 检查是否存在编译错误
     * @param pluginId 插件ID
     * @return 是否存在编译错误
     */
    private boolean hasCompilationErrors(String pluginId) {
        // 简单实现：检查是否存在 .class 文件
        // 更完善的实现应该检查编译器输出或错误日志
        for (Map.Entry<WatchKey, String> entry : keyPluginMap.entrySet()) {
            if (entry.getValue().equals(pluginId)) {
                Path dir = (Path) entry.getKey().watchable();
                try {
                    // 检查目录中是否存在 .class 文件
                    return Files.walk(dir)
                            .filter(path -> path.toString().endsWith(".class"))
                            .findFirst()
                            .isEmpty();
                } catch (IOException e) {
                    log.warn("无法检查编译状态: {}", dir, e);
                }
            }
        }
        return false;
    }
}