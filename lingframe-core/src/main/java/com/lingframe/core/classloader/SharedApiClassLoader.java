package com.lingframe.core.classloader;

import com.lingframe.api.exception.InvalidArgumentException;
import com.lingframe.core.exception.ClassLoaderException;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * 共享 API ClassLoader。
 * 职责：作为灵核 ClassLoader 和 LingClassLoader 之间的中间层，
 * 统一加载各灵元共享的接口、DTO 与契约类。
 * <p>
 * 类加载层级：
 *
 * <pre>
 * 灵核 ClassLoader（JDK / Spring / lingframe-api / 灵核业务）
 *         ↓ parent
 * SharedApiClassLoader（各灵元共享的 API 契约）
 *         ↓ parent
 * LingClassLoader（各灵元实现）
 * </pre>
 * <p>
 * 安全设计：
 * 1. 只有框架 bootstrap 阶段允许向共享层注入 API JAR / classes 目录
 * 2. 加载前记录并检测类冲突，至少把“谁先占坑”说明白
 * 3. 边界 freeze 后禁止继续修改，避免运行时出现共享契约漂移
 */
@Slf4j
public class SharedApiClassLoader extends URLClassLoader {

    // 单例实例。共享层在单进程内只能有一份，否则“共享 API”会退化成多个平行宇宙。
    private static volatile SharedApiClassLoader INSTANCE;

    // 🔒 边界冻结后不允许继续向共享层塞入新契约
    private static volatile boolean BOUNDARY_FROZEN;

    // 已加载的 JAR / classes 目录路径，用于幂等去重
    private final Set<String> loadedJars = ConcurrentHashMap.newKeySet();

    // 类名 -> 来源，用于冲突检测和调试定位
    private final Map<String, String> classSourceMap = new ConcurrentHashMap<>();

    /**
     * 获取单例实例。
     */
    public static synchronized SharedApiClassLoader getInstance(ClassLoader lingCoreClassLoader) {
        if (INSTANCE == null) {
            INSTANCE = new SharedApiClassLoader(lingCoreClassLoader);
            log.info("📦 [SharedApi] SharedApiClassLoader initialized");
        }
        return INSTANCE;
    }

    /**
     * 获取已初始化的实例；未初始化时返回 null。
     */
    public static SharedApiClassLoader getInstanceOrNull() {
        return INSTANCE;
    }

    /**
     * 冻结共享 API 边界。
     */
    public static synchronized void freezeBoundary() {
        if (!BOUNDARY_FROZEN) {
            BOUNDARY_FROZEN = true;
            log.info("🔒 [SharedApi] Shared API classloader boundary frozen");
        }
    }

    public static boolean isBoundaryFrozen() {
        return BOUNDARY_FROZEN;
    }

    /**
     * 仅用于测试和容器关闭后的全量重置。
     */
    public static synchronized void resetInstance() {
        if (INSTANCE != null) {
            try {
                INSTANCE.close();
            } catch (Exception e) {
                log.warn("⚠️ [SharedApi] Error while closing SharedApiClassLoader", e);
            }
            INSTANCE = null;
        }
        BOUNDARY_FROZEN = false;
    }

    private SharedApiClassLoader(ClassLoader parent) {
        super(new URL[0], parent);
    }

    /**
     * 添加 API JAR。
     */
    public void addApiJar(File apiJar) {
        assertBoundaryMutable("add API JAR");
        if (apiJar == null || !apiJar.exists()) {
            throw new InvalidArgumentException("apiJar", "API JAR does not exist: " + apiJar);
        }

        String jarPath = apiJar.getAbsolutePath();
        if (loadedJars.contains(jarPath)) {
            log.debug("[SharedApi] API JAR already loaded, skipping {}", jarPath);
            return;
        }

        // ⚠️ 这里只做“类名冲突预警”，不主动覆盖旧类。
        // Shared API 一旦发生同名契约冲突，强行替换比显式暴露问题更危险。
        try {
            checkClassConflicts(apiJar);
        } catch (Exception e) {
            throw new ClassLoaderException(null, jarPath, "API JAR conflict detection failed", e);
        }

        try {
            addURL(apiJar.toURI().toURL());
            loadedJars.add(jarPath);
            log.info("📦 [SharedApi] Loaded JAR {}", apiJar.getName());
        } catch (MalformedURLException e) {
            throw new ClassLoaderException(null, jarPath, "Failed to add API JAR", e);
        }
    }

    /**
     * 添加 API classes 目录。
     */
    public void addApiClassesDir(File classesDir) {
        assertBoundaryMutable("add API classes directory");
        if (classesDir == null || !classesDir.exists() || !classesDir.isDirectory()) {
            throw new InvalidArgumentException("classesDir", "Invalid classes directory: " + classesDir);
        }

        String dirPath = classesDir.getAbsolutePath();
        if (loadedJars.contains(dirPath)) {
            log.debug("[SharedApi] Classes directory already loaded, skipping {}", dirPath);
            return;
        }

        try {
            // 🔥 只扫描并登记类名，不主动 preload Class 对象，避免过早触发类初始化
            scanClassesDir(classesDir, classesDir, classesDir.getName());
            addURL(classesDir.toURI().toURL());
            loadedJars.add(dirPath);
            log.info("📦 [SharedApi] Loaded classes directory {}", classesDir.getName());
        } catch (MalformedURLException e) {
            throw new ClassLoaderException(null, dirPath, "Failed to add classes directory", e);
        }
    }

    private void assertBoundaryMutable(String action) {
        // ⚠️ freeze 之后再追加共享契约，会让不同时间创建的灵元看到不同 ABI 视图
        if (BOUNDARY_FROZEN) {
            throw new IllegalStateException("Shared API boundary already frozen, cannot " + action);
        }
    }

    /**
     * 扫描 classes 目录。
     * 注意这里只记录类名和来源，不保留 Class 对象本身。
     */
    private void scanClassesDir(File baseDir, File currentDir, String sourceName) {
        File[] files = currentDir.listFiles();
        if (files == null) {
            return;
        }

        for (File file : files) {
            if (file.isDirectory()) {
                scanClassesDir(baseDir, file, sourceName);
                continue;
            }
            if (!file.getName().endsWith(".class")) {
                continue;
            }

            String relativePath = baseDir.toURI().relativize(file.toURI()).getPath();
            String className = relativePath.substring(0, relativePath.length() - 6).replace('/', '.');
            String existingSource = classSourceMap.get(className);
            if (existingSource != null) {
                log.warn("⚠️ [SharedApi] Class conflict detected: {} already loaded from {}", className, existingSource);
            } else {
                classSourceMap.put(className, sourceName);
            }
        }
    }

    /**
     * 检查 JAR 中的类是否与已加载共享契约冲突。
     */
    private void checkClassConflicts(File jarFile) throws Exception {
        try (JarFile jar = new JarFile(jarFile)) {
            String jarName = jarFile.getName();
            jar.stream()
                    .filter(entry -> entry.getName().endsWith(".class"))
                    .map(this::entryToClassName)
                    .forEach(className -> {
                        String existingSource = classSourceMap.get(className);
                        if (existingSource != null) {
                            log.warn("⚠️ [SharedApi] Class conflict detected: {} already loaded from {}, version in {} will be ignored",
                                    className, existingSource, jarName);
                        } else {
                            classSourceMap.put(className, jarName);
                        }
                    });
        }
    }

    /**
     * 将 JAR 条目名转成标准类名。
     */
    private String entryToClassName(JarEntry entry) {
        String name = entry.getName();
        return name.substring(0, name.length() - 6).replace('/', '.');
    }

    public boolean isSharedClass(String className) {
        return classSourceMap.containsKey(className);
    }

    public int getLoadedJarCount() {
        return loadedJars.size();
    }

    public int getSharedClassCount() {
        return classSourceMap.size();
    }

    /**
     * 提取所有共享类的包前缀，供 LingClassLoader 建立强制委派白名单。
     */
    public Set<String> getSharedPackagePrefixes() {
        Set<String> packages = new HashSet<>();
        for (String className : classSourceMap.keySet()) {
            int lastDot = className.lastIndexOf('.');
            if (lastDot > 0) {
                packages.add(className.substring(0, lastDot + 1));
            }
        }
        return packages;
    }

    @Override
    public String toString() {
        return String.format("SharedApiClassLoader[jars=%d, classes=%d, frozen=%s]",
                loadedJars.size(), classSourceMap.size(), BOUNDARY_FROZEN);
    }
}
