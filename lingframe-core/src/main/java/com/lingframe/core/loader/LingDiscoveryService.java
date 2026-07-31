package com.lingframe.core.loader;

import com.lingframe.api.config.LingDefinition;
import com.lingframe.core.config.LingFrameConfig;
import com.lingframe.core.ling.LingLifecycleEngine;
import com.lingframe.core.ling.LingRepository;
import com.lingframe.core.ling.LingRuntime;
import com.lingframe.core.util.PathUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 灵元自动发现服务 (Production Ready)
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
    /** 用于查询灵元是否已有默认实例，决定本次 deploy 的 setAsDefault */
    private final LingRepository lingRepository;

    /**
     * 执行扫描并加载
     */
    public void scanAndLoad() {
        if (!config.isAutoScan()) {
            log.info("AutoScan has bean false.");
            return;
        }
        // 用于记录本次扫描已加载的灵元ID，防止重复加载（实现优先级覆盖）
        Set<String> loadedLingIds = new HashSet<>();
        if (!config.getLingHome().trim().isEmpty()) {
            File homeFile = new File(config.getLingHome());
            File[] files = homeFile.listFiles();
            if (files != null) {
                log.info("Starting ling discovery from {}, count: {}", config.getLingHome(), files.length);
                for (File file : files) {
                    try {
                        // 尝试加载单个灵元
                        installSingle(loadedLingIds, file);
                    } catch (Exception e) {
                        // 🔥捕获异常，只打印日志，不抛出！
                        // 这样坏灵元只会打印报错，不会炸毁主程序
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
                if (config.isDevMode()) {
                    realPath += File.separator + "/target/classes";
                }
                File realFile = PathUtils.resolvePath(realPath, new File(config.getLingHome()));
                try {
                    // 🔥 单个 root 失败不中断整体扫描：坏灵元只打日志，不抛出
                    installSingle(loadedLingIds, realFile);
                } catch (Exception e) {
                    log.error("⚠️ Failed to load ling from root: {}", realFile.getAbsolutePath(), e);
                }
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
                // 并不是一个有效的灵元包，跳过（可能是临时文件或无关文件夹）
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

            // setAsDefault 仅在首次发现（无 runtime）或 runtime 尚无默认实例时置 true，
            // 避免多版本共存场景下后发现的灵元强制覆盖既有默认实例。
            // 与 DashboardLingOperations.installLing 同一判定模式。
            LingRuntime existing = lingRepository != null ? lingRepository.getRuntime(lingId) : null;
            boolean setAsDefault = existing == null
                    || existing.getInstancePool().getDefault() == null;
            lifecycleEngine.deploy(def, file, setAsDefault, Collections.emptyMap());

            loadedLingIds.add(lingId);
        } catch (Exception e) {
            // 捕获单个灵元的异常，避免阻断整个扫描过程
            log.error("Failed to load ling from: {}", file.getAbsolutePath(), e);
        }
    }

    private boolean isValidRoot(File root) {
        if (!root.exists()) {
            log.warn("Ling root does not exist: {}", root.getAbsolutePath());
            return false;
        }
        // 🔥 允许 jar 文件作为灵元根：打包成 jar 的灵元同样需要被自动发现
        // 原实现只接受目录，导致 jar 形态的灵元在 scanAndLoad 时被静默跳过
        if (!root.isDirectory() && !root.getName().endsWith(".jar")) {
            log.warn("Ling root is neither a directory nor a jar: {}", root.getAbsolutePath());
            return false;
        }
        if (!root.canRead()) {
            log.error("Ling root is not readable: {}", root.getAbsolutePath());
            return false;
        }
        return true;
    }

}