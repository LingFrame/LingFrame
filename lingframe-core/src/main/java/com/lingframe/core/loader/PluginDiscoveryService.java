package com.lingframe.core.loader;

import com.lingframe.api.config.PluginDefinition;
import com.lingframe.core.config.LingFrameConfig;
import com.lingframe.core.plugin.PluginManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 插件自动发现服务 (Production Ready)
 * <p>
 * 职责：
 * 1. 扫描配置的所有根目录 (homes)
 * 2. 识别 Jar 包或 exploded 目录
 * 3. 预解析 plugin.yml 获取元数据 (ID, Version)
 * 4. 调用 PluginManager 完成安装
 */
@Slf4j
@RequiredArgsConstructor
public class PluginDiscoveryService {

    private final LingFrameConfig config;
    private final PluginManager pluginManager;

    /**
     * 执行扫描并加载
     */
    public void scanAndLoad() {
        // 用于记录本次扫描已加载的插件ID，防止重复加载（实现优先级覆盖）
        Set<String> loadedPluginIds = new HashSet<>();
        if (!config.getPluginHome().trim().isEmpty()) {
            File homeFile = new File(config.getPluginHome());
            File[] files = homeFile.listFiles();
            if (files != null) {
                log.info("Starting plugin discovery from {}, count: {}", config.getPluginHome(), files.length);
                for (File file : files) {
                    try {
                        // 尝试加载单个插件
                        installSingle(loadedPluginIds, file);
                    } catch (Exception e) {
                        // 🔥捕获异常，只打印日志，不抛出！
                        // 这样坏插件只会打印报错，不会炸毁主程序
                        log.error("⚠️ Failed to load plugin from: {}", file.getAbsolutePath(), e);
                    }
                }
            }
        }

        List<String> roots = config.getPluginRoots();
        if (roots != null && !roots.isEmpty()) {
            log.info("Starting plugin discovery from {}, count: {}", roots, roots.size());
            for (String root : roots) {
                String realPath = root;
                if (LingFrameConfig.current().isDevMode()) {
                    realPath += File.separator + "/target/classes";
                }
                File realFile = new File(realPath);
                installSingle(loadedPluginIds, realFile);
            }
        }

        log.info("Plugin discovery finished. Total loaded: {}", loadedPluginIds.size());
    }

    private void installSingle(Set<String> loadedPluginIds, File file) {
        log.info(file.getAbsolutePath());
        if (!isValidRoot(file)) {
            return;
        }

        try {
            // 尝试解析元数据
            PluginDefinition def = PluginManifestLoader.parseDefinition(file);
            if (def == null) {
                // 并不是一个有效的插件包，跳过（可能是临时文件或无关文件夹）
                return;
            }

            String pluginId = def.getId();
            String version = def.getVersion();

            // 检查冲突与优先级
            if (loadedPluginIds.contains(pluginId)) {
                log.info("Plugin [{}] already loaded from a higher priority root. Skipping duplicate in: {}",
                        pluginId, file.getAbsolutePath());
                return;
            }

            // 执行安装
            log.info("Discovered plugin: {} v{} at {}", pluginId, version, file.getName());

            if (LingFrameConfig.current().isDevMode()) {
                // 开发模式：目录安装
                pluginManager.installDev(def, file);
            } else {
                // 生产模式：Jar 安装
                pluginManager.install(def, file);
            }

            loadedPluginIds.add(pluginId);
        } catch (Exception e) {
            // 捕获单个插件的异常，避免阻断整个扫描过程
            log.error("Failed to load plugin from: {}", file.getAbsolutePath(), e);
        }
    }

    private boolean isValidRoot(File root) {
        if (!root.exists()) {
            log.warn("Plugin root does not exist: {}", root.getAbsolutePath());
            return false;
        }
        if (!root.isDirectory()) {
            log.warn("Plugin root is not a directory: {}", root.getAbsolutePath());
            return false;
        }
        if (!root.canRead()) {
            log.error("Plugin root is not readable: {}", root.getAbsolutePath());
            return false;
        }
        return true;
    }

}