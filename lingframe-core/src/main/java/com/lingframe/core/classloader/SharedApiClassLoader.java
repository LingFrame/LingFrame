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
 * 共享 API ClassLoader
 * 职责：作为宿主 ClassLoader 和插件 ClassLoader 之间的中间层，
 * 加载各插件共享的 API 包（接口 + DTO），实现跨插件类共享
 * <p>
 * 类加载层级：
 * 
 * <pre>
 * 宿主 ClassLoader (JDK, Spring, lingframe-api, 宿主业务)
 *         ↓ parent
 * SharedApiClassLoader (各插件的 -api.jar)
 *         ↓ parent
 * PluginClassLoader (各插件的实现)
 * </pre>
 * <p>
 * 安全设计：
 * 1. 只有宿主/框架可以添加 API JAR，插件不能自行添加
 * 2. 加载前检查类是否已存在，防止覆盖
 * 3. 记录已加载的 API JAR，防止重复加载
 */
@Slf4j
public class SharedApiClassLoader extends URLClassLoader {

    // 单例实例
    private static volatile SharedApiClassLoader INSTANCE;

    // 已加载的 API JAR 路径（防止重复加载）
    private final Set<String> loadedJars = ConcurrentHashMap.newKeySet();

    // 已加载的类名 -> 来源 JAR（用于冲突检测和调试）
    private final Map<String, String> classSourceMap = new ConcurrentHashMap<>();

    /**
     * 获取单例实例
     * 首次调用时创建，使用宿主 ClassLoader 作为 parent
     *
     * @param hostClassLoader 宿主 ClassLoader
     * @return 共享 API ClassLoader 实例
     */
    public static synchronized SharedApiClassLoader getInstance(ClassLoader hostClassLoader) {
        if (INSTANCE == null) {
            INSTANCE = new SharedApiClassLoader(hostClassLoader);
            log.info("📦 [LingFrame] SharedApiClassLoader 已初始化");
        }
        return INSTANCE;
    }

    /**
     * 获取已初始化的实例（如果未初始化返回 null）
     */
    public static SharedApiClassLoader getInstanceOrNull() {
        return INSTANCE;
    }

    /**
     * 重置实例（仅用于测试）
     */
    public static synchronized void resetInstance() {
        if (INSTANCE != null) {
            try {
                INSTANCE.close();
            } catch (Exception e) {
                log.warn("关闭 SharedApiClassLoader 时出错", e);
            }
            INSTANCE = null;
        }
    }

    private SharedApiClassLoader(ClassLoader parent) {
        super(new URL[0], parent);
    }

    /**
     * 添加 API JAR 到共享类加载器
     * 只有通过此方法添加的 JAR 中的类才能被共享访问
     *
     * @param apiJar API JAR 文件
     * @throws IllegalStateException 如果 JAR 无效或包含冲突的类
     */
    public void addApiJar(File apiJar) {
        if (apiJar == null || !apiJar.exists()) {
            throw new InvalidArgumentException("apiJar", "API JAR 不存在: " + apiJar);
        }

        String jarPath = apiJar.getAbsolutePath();

        // 防止重复加载
        if (loadedJars.contains(jarPath)) {
            log.debug("API JAR 已加载，跳过: {}", jarPath);
            return;
        }

        // 冲突检测：扫描 JAR 中的类，检查是否与已加载的类冲突
        try {
            checkClassConflicts(apiJar);
        } catch (Exception e) {
            throw new ClassLoaderException(null, jarPath, "API JAR 冲突检测失败", e);
        }

        // 添加 URL
        try {
            addURL(apiJar.toURI().toURL());
            loadedJars.add(jarPath);
            log.info("📦 [SharedApi] JAR 已加载: {}", apiJar.getName());
        } catch (MalformedURLException e) {
            throw new ClassLoaderException(null, jarPath, "无法添加 API JAR", e);
        }
    }

    /**
     * 添加 API classes 目录到共享类加载器
     * 用于开发模式下加载未打包的类文件
     *
     * @param classesDir classes 目录
     * @throws IllegalStateException 如果目录无效
     */
    public void addApiClassesDir(File classesDir) {
        if (classesDir == null || !classesDir.exists() || !classesDir.isDirectory()) {
            throw new InvalidArgumentException("classesDir", "classes 目录无效: " + classesDir);
        }

        String dirPath = classesDir.getAbsolutePath();

        // 防止重复加载
        if (loadedJars.contains(dirPath)) {
            log.debug("classes 目录已加载，跳过: {}", dirPath);
            return;
        }

        try {
            // 扫描目录中的类文件并记录
            scanClassesDir(classesDir, classesDir, classesDir.getName());

            // 添加 URL
            addURL(classesDir.toURI().toURL());
            loadedJars.add(dirPath);
            log.info("📦 [SharedApi] classes 目录已加载: {}", classesDir.getName());
        } catch (MalformedURLException e) {
            throw new ClassLoaderException(null, dirPath, "无法添加 classes 目录", e);
        }
    }

    /**
     * 扫描 classes 目录中的类文件
     */
    private void scanClassesDir(File baseDir, File currentDir, String sourceName) {
        File[] files = currentDir.listFiles();
        if (files == null)
            return;

        for (File file : files) {
            if (file.isDirectory()) {
                scanClassesDir(baseDir, file, sourceName);
            } else if (file.getName().endsWith(".class")) {
                // 计算类名
                String relativePath = baseDir.toURI().relativize(file.toURI()).getPath();
                String className = relativePath.substring(0, relativePath.length() - 6).replace('/', '.');

                String existingSource = classSourceMap.get(className);
                if (existingSource != null) {
                    log.warn("⚠️ 类冲突: {} 已由 {} 加载", className, existingSource);
                } else {
                    classSourceMap.put(className, sourceName);
                }
            }
        }
    }

    /**
     * 检查 JAR 中的类是否与已加载的类冲突
     */
    private void checkClassConflicts(File jarFile) throws Exception {
        try (JarFile jar = new JarFile(jarFile)) {
            String jarName = jarFile.getName();
            jar.stream()
                    .filter(entry -> entry.getName().endsWith(".class"))
                    .map(this::entryToClassName)
                    .forEach(className -> {
                        // 检查是否已被其他 JAR 加载
                        String existingSource = classSourceMap.get(className);
                        if (existingSource != null) {
                            log.warn("⚠️ 类冲突: {} 已由 {} 加载，{} 中的版本将被忽略",
                                    className, existingSource, jarName);
                        } else {
                            classSourceMap.put(className, jarName);
                        }
                    });
        }
    }

    /**
     * 将 JAR 条目名称转换为类名
     */
    private String entryToClassName(JarEntry entry) {
        String name = entry.getName();
        // 移除 .class 后缀并将 / 替换为 .
        return name.substring(0, name.length() - 6).replace('/', '.');
    }

    /**
     * 检查指定类是否由共享 ClassLoader 加载
     */
    public boolean isSharedClass(String className) {
        return classSourceMap.containsKey(className);
    }

    /**
     * 获取已加载的 API JAR 数量
     */
    public int getLoadedJarCount() {
        return loadedJars.size();
    }

    /**
     * 获取已加载的共享类数量
     */
    public int getSharedClassCount() {
        return classSourceMap.size();
    }

    /**
     * 获取所有共享类的包前缀（用于 PluginClassLoader 委派）
     * 返回去重后的包名前缀列表
     */
    public Set<String> getSharedPackagePrefixes() {
        Set<String> packages = new HashSet<>();
        for (String className : classSourceMap.keySet()) {
            int lastDot = className.lastIndexOf('.');
            if (lastDot > 0) {
                // 获取包名并加上点号作为前缀
                String packageName = className.substring(0, lastDot + 1);
                packages.add(packageName);
            }
        }
        return packages;
    }

    @Override
    public String toString() {
        return String.format("SharedApiClassLoader[jars=%d, classes=%d]",
                loadedJars.size(), classSourceMap.size());
    }
}
