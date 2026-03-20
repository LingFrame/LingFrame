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
 * 共享 API 管理器。
 * 职责：管理 SharedApiClassLoader 中的共享 API，支持启动期预加载和冻结边界。
 * <p>
 * 架构设计：三层 ClassLoader 结构
 *
 * <pre>
 * 灵核 ClassLoader（灵核 / Spring / 灵核业务）
 *     ↓ parent
 * SharedApiClassLoader（共享 API 层，只放接口 / DTO / 契约）
 *     ↓ parent
 * LingClassLoader（灵元实现层）
 * </pre>
 * <p>
 * ⚠️ Shared API 的本质不是“多一个方便加载目录”，而是“单进程内统一的契约边界”。
 * 先 preload，再 freeze，最后才允许灵元正式装载；顺序反了，就会出现部分灵元看到旧边界、部分灵元看到新边界的问题。
 * <p>
 * 配置 preload-api-jars 支持：
 * - JAR 文件
 * - classes 目录
 * - Maven 模块目录（自动识别 target/classes）
 * - JAR 目录
 * - 通配符模式
 */
@Slf4j
public class SharedApiManager {

    private final ClassLoader lingCoreClassLoader;
    private final LingFrameConfig config;

    // 🔒 freeze 之后不允许继续增改共享契约，避免运行中途把“共享边界”改成漂移边界
    private volatile boolean frozen;

    public SharedApiManager(ClassLoader lingCoreClassLoader, LingFrameConfig config) {
        this.lingCoreClassLoader = lingCoreClassLoader;
        this.config = config;
    }

    /**
     * 获取 SharedApiClassLoader 实例。
     */
    public SharedApiClassLoader getSharedApiClassLoader() {
        return SharedApiClassLoader.getInstance(lingCoreClassLoader);
    }

    /**
     * 根据配置预加载共享 API。
     * 该方法只允许在 bootstrap 阶段调用。
     */
    public void preloadFromConfig() {
        ensureMutable("preload shared APIs");

        List<String> apiPaths = config.getPreloadApiJars();
        if (apiPaths == null || apiPaths.isEmpty()) {
            log.debug("[SharedApi] No preload paths configured, skipping initialization");
            return;
        }

        SharedApiClassLoader sharedApiClassLoader = getSharedApiClassLoader();
        File lingHomeDir = new File(config.getLingHome());

        for (String path : apiPaths) {
            try {
                log.info("🔍 [SharedApi] Preloading path {}", new File(path).getAbsolutePath());
                loadPath(path, lingHomeDir, sharedApiClassLoader);
            } catch (Exception e) {
                log.error("❌ [SharedApi] Failed to load path {}", path, e);
            }
        }

        registerSharedPackages(sharedApiClassLoader);
        log.info("📦 [SharedApi] Bootstrap load complete: jars={}, classes={}",
                sharedApiClassLoader.getLoadedJarCount(),
                sharedApiClassLoader.getSharedClassCount());
    }

    /**
     * 冻结共享 API 边界。
     * 冻结后不再允许新增共享 API JAR、classes 目录和共享包前缀。
     * ⚠️ 这是“共享契约定版”动作，不是普通的状态切换。
     */
    public synchronized void freezeSharedBoundary() {
        if (frozen) {
            return;
        }
        SharedApiClassLoader.freezeBoundary();
        LingClassLoader.freezeSharedApiBoundary();
        frozen = true;
        log.info("🔒 [SharedApi] Shared API boundary frozen");
    }

    public boolean isFrozen() {
        return frozen;
    }

    private void registerSharedPackages(SharedApiClassLoader sharedApiClassLoader) {
        Set<String> sharedPackages = sharedApiClassLoader.getSharedPackagePrefixes();
        if (!sharedPackages.isEmpty()) {
            // ⚠️ SharedApiClassLoader 负责真正持有类，LingClassLoader 这里只维护“必须委派”的包前缀视图
            LingClassLoader.addSharedApiPackages(sharedPackages);
            log.info("📦 [SharedApi] Registered shared package prefixes: {}", sharedPackages);
        }
    }

    /**
     * 加载单个路径（自动识别文件 / 目录 / 通配符）。
     */
    private void loadPath(String path, File lingHomeDir, SharedApiClassLoader sharedApiClassLoader) {
        // 🔥 允许使用通配符批量收敛 API 契约包，方便开发期快速聚合
        if (containsWildcard(path)) {
            loadWildcardPath(path, lingHomeDir, sharedApiClassLoader);
            return;
        }

        File file = resolvePath(path, lingHomeDir);
        if (file == null || !file.exists()) {
            log.warn("⚠️ [SharedApi] Path not found: {}", path);
            return;
        }

        if (file.isDirectory()) {
            loadDirectory(file, sharedApiClassLoader);
        } else if (file.getName().endsWith(".jar")) {
            sharedApiClassLoader.addApiJar(file);
            log.info("📦 [SharedApi] Loaded JAR {}", file.getName());
        } else {
            log.warn("⚠️ [SharedApi] Unsupported file type: {}", path);
        }
    }

    /**
     * 加载目录（自动判断是 Maven 模块、JAR 目录还是 classes 目录）。
     */
    private void loadDirectory(File dir, SharedApiClassLoader sharedApiClassLoader) {
        // 1. 优先识别 Maven 模块目录，开发期最常见
        File pomFile = new File(dir, "pom.xml");
        if (pomFile.exists()) {
            File classesDir = new File(dir, "target/classes");
            if (classesDir.exists() && classesDir.isDirectory()) {
                sharedApiClassLoader.addApiClassesDir(classesDir);
                log.info("📦 [SharedApi] Loaded Maven module {}/target/classes", dir.getName());
            } else {
                log.warn("⚠️ [SharedApi] Maven module {} is missing target/classes, run mvn compile first",
                        dir.getName());
            }
            return;
        }

        // 2. 其次识别 JAR 仓目录
        File[] jarFiles = dir.listFiles((currentDir, name) -> name.endsWith(".jar"));
        if (jarFiles != null && jarFiles.length > 0) {
            for (File jar : jarFiles) {
                sharedApiClassLoader.addApiJar(jar);
            }
            log.info("📦 [SharedApi] Loaded {} JARs from directory {}", jarFiles.length, dir.getName());
            return;
        }

        // 3. 最后按普通 classes 目录处理
        sharedApiClassLoader.addApiClassesDir(dir);
        log.info("📦 [SharedApi] Loaded classes directory {}", dir.getName());
    }

    /**
     * 检查路径是否包含通配符。
     */
    private boolean containsWildcard(String path) {
        return path.contains("*") || path.contains("?");
    }

    /**
     * 加载通配符匹配的路径。
     */
    private void loadWildcardPath(String pattern, File lingHomeDir, SharedApiClassLoader sharedApiClassLoader) {
        // 分离目录部分和文件模式
        int lastSeparator = Math.max(pattern.lastIndexOf('/'), pattern.lastIndexOf('\\'));
        String dirPart = lastSeparator > 0 ? pattern.substring(0, lastSeparator) : ".";
        String filePattern = lastSeparator > 0 ? pattern.substring(lastSeparator + 1) : pattern;

        File dir = resolvePath(dirPart, lingHomeDir);
        if (dir == null || !dir.exists() || !dir.isDirectory()) {
            log.warn("⚠️ [SharedApi] Wildcard base directory not found: {}", dirPart);
            return;
        }

        PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + filePattern);
        File[] matches = dir.listFiles((currentDir, name) -> matcher.matches(Paths.get(name)));
        if (matches == null || matches.length == 0) {
            log.warn("⚠️ [SharedApi] No files matched pattern {}", pattern);
            return;
        }

        int loaded = 0;
        for (File match : matches) {
            try {
                if (match.isDirectory()) {
                    loadDirectory(match, sharedApiClassLoader);
                } else if (match.getName().endsWith(".jar")) {
                    sharedApiClassLoader.addApiJar(match);
                }
                loaded++;
            } catch (Exception e) {
                log.error("❌ [SharedApi] Failed to load wildcard match {}", match.getName(), e);
            }
        }
        log.info("📦 [SharedApi] Wildcard pattern {} matched {} entries", pattern, loaded);
    }

    /**
     * 动态添加共享 API。
     * 该入口只允许在 bootstrap 冻结前使用。
     */
    public boolean addApi(File file) {
        ensureMutable("add shared API");

        try {
            SharedApiClassLoader sharedApiClassLoader = getSharedApiClassLoader();
            if (file.isDirectory()) {
                sharedApiClassLoader.addApiClassesDir(file);
            } else {
                sharedApiClassLoader.addApiJar(file);
            }
            registerSharedPackages(sharedApiClassLoader);
            log.info("📦 [SharedApi] Added shared API {}", file.getName());
            return true;
        } catch (Exception e) {
            log.error("❌ [SharedApi] Failed to add shared API {}", file.getName(), e);
            return false;
        }
    }

    public int addApis(List<File> files) {
        int successCount = 0;
        for (File file : files) {
            if (addApi(file)) {
                successCount++;
            }
        }
        return successCount;
    }

    public boolean isSharedClass(String className) {
        return getSharedApiClassLoader().isSharedClass(className);
    }

    public String getStats() {
        SharedApiClassLoader classLoader = getSharedApiClassLoader();
        return String.format("SharedApiClassLoader[loaded=%d, classes=%d, frozen=%s]",
                classLoader.getLoadedJarCount(),
                classLoader.getSharedClassCount(),
                frozen);
    }

    public void shutdown() {
        frozen = false;
        LingClassLoader.resetSharedApiBoundary();
        SharedApiClassLoader.resetInstance();
    }

    private void ensureMutable(String action) {
        // ⚠️ 一旦 freeze，再往里塞契约包就等于“边跑边改 ABI”，必须硬失败
        if (frozen) {
            throw new IllegalStateException("Shared API boundary already frozen, cannot " + action);
        }
    }

    /**
     * 解析路径（支持绝对路径、相对当前工作目录、相对 lingHome 路径）。
     * 始终返回规范化后的绝对路径。
     */
    private File resolvePath(String path, File lingHomeDir) {
        if (path == null || path.trim().isEmpty()) {
            return null;
        }

        File file = new File(path);

        // 1. 如果已经是绝对路径，直接使用
        if (file.isAbsolute()) {
            return getTypeSafeFile(file);
        }

        // 2. 优先尝试相对当前工作目录，开发模式下最常见
        if (file.exists()) {
            return getTypeSafeFile(file);
        }

        // 3. 再尝试相对 lingHome
        File lingFile = new File(lingHomeDir, path);
        if (lingFile.exists()) {
            return getTypeSafeFile(lingFile);
        }

        // 4. 即便不存在，也返回标准化后的目标路径，方便上层统一输出错误信息
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
