package com.lingframe.core.loader;

import com.lingframe.api.config.LingDefinition;
import com.lingframe.core.config.LingFrameConfig;
import com.lingframe.core.ling.LingLifecycleEngine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 单元自动发现服务 (Production Ready)
 * <p>
 * 职责：
 * 1. 扫描配置的所有根目录 (homes)
 * 2. 识别 Jar 包或 exploded 目录
 * 3. 预解析 ling.yml 获取元数据 (ID, Version)
 * 4. 调用 LingManager 完成安装
 */
@Slf4j
@RequiredArgsConstructor
public class LingDiscoveryService {

    private final LingFrameConfig config;
    private final LingLifecycleEngine lifecycleEngine;

    /**
     * 执行扫描并加载
     */
    public void scanAndLoad() {
        if (!config.isAutoScan()) {
            log.info("AutoScan has bean false.");
            return;
        }
        // 用于记录本次扫描已加载的单元ID，防止重复加载（实现优先级覆盖）
        Set<String> loadedLingIds = new HashSet<>();
        if (!config.getLingHome().trim().isEmpty()) {
            File homeFile = new File(config.getLingHome());
            File[] files = homeFile.listFiles();
            if (files != null) {
                log.info("Starting ling discovery from {}, count: {}", config.getLingHome(), files.length);
                for (File file : files) {
                    try {
                        // 尝试加载单个单元
                        installSingle(loadedLingIds, file);
                    } catch (Exception e) {
                        // 🔥捕获异常，只打印日志，不抛出！
                        // 这样坏单元只会打印报错，不会炸毁主程序
                        log.error("⚠️ Failed to load ling from: {}", file.getAbsolutePath(), e);
                    }
                }
            }
        }

        List<String> roots = config.getLingRoots();
        if (roots != null && !roots.isEmpty()) {
            log.info("Starting ling discovery from {}, count: {}", roots, roots.size());
            for (String root : roots) {
                String realPath = root;
                if (LingFrameConfig.current().isDevMode()) {
                    realPath += File.separator + "/target/classes";
                }
                File realFile = new File(realPath);
                installSingle(loadedLingIds, realFile);
            }
        }

        log.info("Ling discovery finished. Total loaded: {}", loadedLingIds.size());
    }

    private void installSingle(Set<String> loadedLingIds, File file) {
        log.info(file.getAbsolutePath());
        if (!isValidRoot(file)) {
            return;
        }

        try {
            // 尝试解析元数据
            LingDefinition def = LingManifestLoader.parseDefinition(file);
            if (def == null) {
                // 并不是一个有效的单元包，跳过（可能是临时文件或无关文件夹）
                return;
            }

            String lingId = def.getId();
            String version = def.getVersion();

            // 检查冲突与优先级
            // if (loadedLingIds.contains(lingId)) {
            // log.info("Ling [{}] already loaded from a higher priority root. Skipping
            // duplicate in: {}",
            // lingId, file.getAbsolutePath());
            // return;
            // }

            // 执行安装
            log.info("Discovered ling: {} v{} at {}", lingId, version, file.getName());

            // 检查是否为金丝雀版本
            if (def.getProperties() != null && Boolean.TRUE.equals(def.getProperties().get("canary"))) {
                lifecycleEngine.deploy(def, file, false, Collections.emptyMap());
                loadedLingIds.add(lingId);
                return;
            }

            // 开发/生产模式由于引擎逻辑在内部判定，只需一致传 isDefault=true
            lifecycleEngine.deploy(def, file, true, Collections.emptyMap());

            loadedLingIds.add(lingId);
        } catch (Exception e) {
            // 捕获单个单元的异常，避免阻断整个扫描过程
            log.error("Failed to load ling from: {}", file.getAbsolutePath(), e);
        }
    }

    private boolean isValidRoot(File root) {
        if (!root.exists()) {
            log.warn("Ling root does not exist: {}", root.getAbsolutePath());
            return false;
        }
        if (!root.isDirectory()) {
            log.warn("Ling root is not a directory: {}", root.getAbsolutePath());
            return false;
        }
        if (!root.canRead()) {
            log.error("Ling root is not readable: {}", root.getAbsolutePath());
            return false;
        }
        return true;
    }

}