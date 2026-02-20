package com.lingframe.core.classloader;

import com.lingframe.core.config.LingFrameConfig;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.nio.file.FileSystems;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;

/**
 * 共享 API 管理服务
 * 职责：管理 SharedApiClassLoader 中的共享 API，支持启动时预加载和动态添加
 * <p>
 * 架构设计：三层 ClassLoader 结构
 * 
 * <pre>
 * 灵核 ClassLoader (AppClassLoader)
 *     ↓ parent
 * SharedApiClassLoader (共享 API 层)
 *     ↓ parent
 * LingClassLoader (单元实现层)
 * </pre>
 * <p>
 * 配置 preload-api-jars 指定共享 API 路径，支持：
 * - JAR 文件、Maven 单元目录、JAR 目录、通配符模式
 */
@Slf4j
public class SharedApiManager {

    private final ClassLoader hostClassLoader;
    private final LingFrameConfig config;

    public SharedApiManager(ClassLoader hostClassLoader, LingFrameConfig config) {
        this.hostClassLoader = hostClassLoader;
        this.config = config;
    }

    /**
     * 获取 SharedApiClassLoader 实例
     */
    public SharedApiClassLoader getSharedApiClassLoader() {
        return SharedApiClassLoader.getInstance(hostClassLoader);
    }

    /**
     * 从配置预加载 API
     * 在应用启动时调用
     */
    public void preloadFromConfig() {
        List<String> apiPaths = config.getPreloadApiJars();
        if (apiPaths == null || apiPaths.isEmpty()) {
            log.debug("Preload path not configured, skipping shared API initialization");
            return;
        }

        SharedApiClassLoader sharedApiCL = getSharedApiClassLoader();
        File lingHomeDir = new File(config.getLingHome());

        for (String path : apiPaths) {
            try {
                loadPath(path, lingHomeDir, sharedApiCL);
            } catch (Exception e) {
                log.error("❌ [SharedApi] Load failed: {}", path, e);
            }
        }

        // 将共享 API 包前缀注册到 LingClassLoader，使其强制委派给 SharedApiClassLoader
        Set<String> sharedPackages = sharedApiCL.getSharedPackagePrefixes();
        if (!sharedPackages.isEmpty()) {
            LingClassLoader.addSharedApiPackages(sharedPackages);
        }

        log.info("📦 [SharedApi] Initialization complete - Loaded: {}, Shared classes: {}, Shared packages: {}",
                sharedApiCL.getLoadedJarCount(), sharedApiCL.getSharedClassCount(), sharedPackages);
    }

    /**
     * 加载单个路径（自动检测类型）
     * 支持:
     * - JAR 文件
     * - classes 目录 (直接包含 .class 文件)
     * - Maven 单元目录 (包含 pom.xml 且有 target/classes)
     * - JAR 目录 (包含多个 JAR，自动扫描所有 *.jar)
     * - 通配符模式 (如 libs/*-api.jar)
     */
    private void loadPath(String path, File lingHomeDir, SharedApiClassLoader sharedApiCL) {
        // 🔥 支持通配符模式
        if (containsWildcard(path)) {
            loadWildcardPath(path, lingHomeDir, sharedApiCL);
            return;
        }

        File file = resolvePath(path, lingHomeDir);
        if (file == null || !file.exists()) {
            log.warn("⚠️ [SharedApi] Path not found: {}", path);
            return;
        }

        if (file.isDirectory()) {
            loadDirectory(file, sharedApiCL);
        } else if (file.getName().endsWith(".jar")) {
            sharedApiCL.addApiJar(file);
            log.info("📦 [SharedApi] JAR loaded: {}", file.getName());
        } else {
            log.warn("⚠️ [SharedApi] Unsupported file type: {}", path);
        }
    }

    /**
     * 加载目录（自动检测目录类型）
     */
    private void loadDirectory(File dir, SharedApiClassLoader sharedApiCL) {
        // 1. 检查是否是 Maven 单元目录
        File pomFile = new File(dir, "pom.xml");
        if (pomFile.exists()) {
            File classesDir = new File(dir, "target/classes");
            if (classesDir.exists() && classesDir.isDirectory()) {
                sharedApiCL.addApiClassesDir(classesDir);
                log.info("📦 [SharedApi] Maven unit loaded: {}/target/classes", dir.getName());
            } else {
                log.warn("⚠️ [SharedApi] Maven unit target/classes missing: {}, please run mvn compile first",
                        dir.getName());
            }
            return;
        }

        // 2. 检查目录是否包含 JAR 文件
        File[] jarFiles = dir.listFiles((d, name) -> name.endsWith(".jar"));
        if (jarFiles != null && jarFiles.length > 0) {
            // 扫描目录下所有 JAR
            for (File jar : jarFiles) {
                sharedApiCL.addApiJar(jar);
            }
            log.info("📦 [SharedApi] Directory scan complete: {} ({} JARs found)", dir.getName(), jarFiles.length);
            return;
        }

        // 3. 作为 classes 目录处理
        sharedApiCL.addApiClassesDir(dir);
        log.info("📦 [SharedApi] classes directory loaded: {}", dir.getName());
    }

    /**
     * 检查路径是否包含通配符
     */
    private boolean containsWildcard(String path) {
        return path.contains("*") || path.contains("?");
    }

    /**
     * 加载通配符匹配的路径
     * 支持: libs/*-api.jar, units/
     */
    private void loadWildcardPath(String pattern, File lingHomeDir, SharedApiClassLoader sharedApiCL) {
        // 分离目录部分和文件名模式
        int lastSep = Math.max(pattern.lastIndexOf('/'), pattern.lastIndexOf('\\'));
        String dirPart = lastSep > 0 ? pattern.substring(0, lastSep) : ".";
        String filePattern = lastSep > 0 ? pattern.substring(lastSep + 1) : pattern;

        // 解析目录
        File dir = resolvePath(dirPart, lingHomeDir);
        if (dir == null || !dir.exists() || !dir.isDirectory()) {
            log.warn("⚠️ [SharedApi] Wildcard base directory not found: {}", dirPart);
            return;
        }

        // 创建 PathMatcher
        PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + filePattern);

        // 扫描匹配的文件/目录
        File[] matches = dir.listFiles((d, name) -> matcher.matches(Paths.get(name)));
        if (matches == null || matches.length == 0) {
            log.warn("⚠️ [SharedApi] No matching files found: {}", pattern);
            return;
        }

        int count = 0;
        for (File match : matches) {
            try {
                if (match.isDirectory()) {
                    loadDirectory(match, sharedApiCL);
                } else if (match.getName().endsWith(".jar")) {
                    sharedApiCL.addApiJar(match);
                }
                count++;
            } catch (Exception e) {
                log.error("❌ [SharedApi] Load failed: {}", match.getName(), e);
            }
        }
        log.info("📦 [SharedApi] Wildcard matched: {} ({} found)", pattern, count);
    }

    /**
     * 动态添加 API（JAR 或目录）
     *
     * @param file API JAR 或 classes 目录
     * @return 是否添加成功
     */
    public boolean addApi(File file) {
        try {
            SharedApiClassLoader sharedApiCL = getSharedApiClassLoader();
            if (file.isDirectory()) {
                sharedApiCL.addApiClassesDir(file);
            } else {
                sharedApiCL.addApiJar(file);
            }
            log.info("📦 [SharedApi] Dynamically added: {}", file.getName());
            return true;
        } catch (Exception e) {
            log.error("❌ [SharedApi] Dynamic add failed: {}", file.getName(), e);
            return false;
        }
    }

    /**
     * 批量动态添加
     */
    public int addApis(List<File> files) {
        int successCount = 0;
        for (File file : files) {
            if (addApi(file)) {
                successCount++;
            }
        }
        return successCount;
    }

    /**
     * 检查指定类是否在共享层中
     */
    public boolean isSharedClass(String className) {
        return getSharedApiClassLoader().isSharedClass(className);
    }

    /**
     * 获取统计信息
     */
    public String getStats() {
        SharedApiClassLoader cl = getSharedApiClassLoader();
        return String.format("SharedApiClassLoader[loaded=%d, classes=%d]",
                cl.getLoadedJarCount(), cl.getSharedClassCount());
    }

    /**
     * 解析路径（支持绝对路径、相对 CWD 路径、相对 lingHome 路径）
     * 始终返回规范化的绝对路径
     */
    private File resolvePath(String path, File lingHomeDir) {
        if (path == null || path.trim().isEmpty()) {
            return null;
        }

        File file = new File(path);

        // 1. 如果是绝对路径，直接返回
        if (file.isAbsolute()) {
            return getTypeSafeFile(file);
        }

        // 2. 尝试作为相对于当前工作目录（CWD）的路径
        // 开发模式下，经常配置相对于项目根目录的路径
        if (file.exists()) {
            return getTypeSafeFile(file);
        }

        // 3. 尝试相对于 lingHomeDir
        File lingFile = new File(lingHomeDir, path);
        if (lingFile.exists()) {
            return getTypeSafeFile(lingFile);
        }

        // 4. 都不存在，返回相对于 lingHome 的路径（用于后续报错）
        return getTypeSafeFile(lingFile);
    }

    private File getTypeSafeFile(File file) {
        try {
            return file.getCanonicalFile();
        } catch (Exception e) {
            return file.getAbsoluteFile();
        }
    }
}
