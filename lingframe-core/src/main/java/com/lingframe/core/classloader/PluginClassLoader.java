package com.lingframe.core.classloader;

import lombok.extern.slf4j.Slf4j;
import com.lingframe.core.exception.ClassLoaderException;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 插件类加载器
 * 特性：
 * 1. Child-First (优先加载插件内部类)
 * 2. 强制委派白名单 (Core API 必须走父加载器)
 * 3. 资源加载 Child-First (防止读取到宿主的配置)
 * 4. 安全关闭 (防止关闭后继续使用)
 */
@Slf4j
public class PluginClassLoader extends URLClassLoader {

    // 必须强制走父加载器的包（契约包 + JDK + 共享 API）
    private static final List<String> FORCE_PARENT_PACKAGES = Arrays.asList(
            "java.", "javax.", "jakarta.", "jdk.", "sun.", "com.sun.", "org.w3c.", "org.xml.",
            "com.lingframe.api.", // API 契约必须共享
            "lombok.", // Lombok 相关类
            "org.slf4j.", // 日志门面通常共享
            "org.apache.logging.log4j.", // Log4j2
            "ch.qos.logback.", // Logback
            "org.springframework.", // Spring框架相关类
            "com.fasterxml.jackson.", // Jackson JSON处理
            "org.yaml.snakeyaml." // SnakeYAML
    );

    // 共享 API 包前缀（可动态添加，优先委派给 SharedApiClassLoader）
    private static final List<String> sharedApiPackages = new CopyOnWriteArrayList<>();

    // 可配置的额外委派包列表
    private static final List<String> additionalParentPackages = new CopyOnWriteArrayList<>();

    // ==================== 实例状态 ====================

    private final String pluginId;
    private volatile boolean closed = false;

    public PluginClassLoader(URL[] urls, ClassLoader parent) {
        this("unknown", urls, parent);
    }

    public PluginClassLoader(String pluginId, URL[] urls, ClassLoader parent) {
        super(urls, parent);
        this.pluginId = pluginId;
        log.debug("[{}] ClassLoader created with {} URLs", pluginId, urls.length);
    }

    /**
     * 添加额外的强制委派包（全局生效）
     *
     * @param packages 包名前缀列表
     */
    public static void addParentDelegatePackages(Collection<String> packages) {
        if (packages != null) {
            additionalParentPackages.addAll(packages);
            log.info("Added parent delegate packages: {}", packages);
        }
    }

    /**
     * 移除额外的委派包
     */
    public static void removeParentDelegatePackages(Collection<String> packages) {
        if (packages != null) {
            additionalParentPackages.removeAll(packages);
        }
    }

    /**
     * 添加共享 API 包前缀（这些包的类将委派给 SharedApiClassLoader 加载）
     *
     * @param packages 共享 API 包名前缀列表
     */
    public static void addSharedApiPackages(Collection<String> packages) {
        if (packages != null) {
            sharedApiPackages.addAll(packages);
            log.info("📦 Added shared API packages: {}", packages);
        }
    }

    /**
     * 清空共享 API 包列表
     */
    public static void clearSharedApiPackages() {
        sharedApiPackages.clear();
    }

    @Override
    public Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        // ✅ 关闭状态检查
        if (closed) {
            throw new ClassLoaderException(pluginId, name,
                    String.format("ClassLoader for plugin [%s] has been closed, cannot load class: %s",
                            pluginId, name));
        }

        synchronized (getClassLoadingLock(name)) {
            // 检查缓存
            Class<?> c = findLoadedClass(name);
            if (c != null)
                return c;

            // 白名单强制委派给父加载器 (防止 ClassCastException)
            if (shouldDelegateToParent(name)) {
                try {
                    c = getParent().loadClass(name);
                    if (c != null) {
                        if (resolve)
                            resolveClass(c);
                        return c;
                    }
                } catch (ClassNotFoundException ignored) {
                    // 父加载器没找到，继续尝试自己加载
                }
            }

            // Child-First: 优先自己加载
            try {
                c = findClass(name);
                if (resolve)
                    resolveClass(c);
                return c;
            } catch (ClassNotFoundException ignored) {
                // 自己没有，继续兜底
            }

            // 兜底: 自己没有，再找父亲 (加载公共库如 StringUtils)
            return super.loadClass(name, resolve);
        }
    }

    @Override
    public URL getResource(String name) {
        if (closed) {
            log.warn("[{}] Attempting to get resource from closed ClassLoader: {}", pluginId, name);
            return null;
        }
        // 资源加载也必须 Child-First，否则会读到宿主的 application.properties
        URL url = findResource(name);
        if (url != null)
            return url;
        return super.getResource(name);
    }

    @Override
    public Enumeration<URL> getResources(String name) throws IOException {
        if (closed) {
            return Collections.emptyEnumeration();
        }
        // 组合资源：自己的 + 父加载器的（自己的优先）
        List<URL> urls = new ArrayList<>();

        // 先添加自己的资源
        Enumeration<URL> localUrls = findResources(name);
        while (localUrls.hasMoreElements())
            urls.add(localUrls.nextElement());
        // 再添加父加载器的资源
        ClassLoader parent = getParent();
        if (parent != null) {
            Enumeration<URL> parentUrls = parent.getResources(name);
            while (parentUrls.hasMoreElements()) {
                URL url = parentUrls.nextElement();
                // 去重
                if (!urls.contains(url)) {
                    urls.add(url);
                }
            }
        }
        return Collections.enumeration(urls);
    }

    @Override
    public void close() throws IOException {
        if (closed) {
            log.debug("[{}] ClassLoader already closed", pluginId);
            return;
        }

        closed = true;
        log.info("[{}] Closing ClassLoader...", pluginId);

        try {
            // 调用父类的 close() 释放 JAR 文件句柄
            super.close();
            log.info("[{}] ClassLoader closed successfully", pluginId);

            // Windows 特殊处理：提示 GC 尽快回收
            System.gc();
        } catch (IOException e) {
            log.error("[{}] Error closing ClassLoader", pluginId, e);
            throw e;
        }
    }

    /**
     * 检查 ClassLoader 是否已关闭
     */
    public boolean isClosed() {
        return closed;
    }

    /**
     * 获取插件ID
     */
    public String getPluginId() {
        return pluginId;
    }

    private boolean shouldDelegateToParent(String name) {
        // ✅ 检查内置白名单
        for (String pkg : FORCE_PARENT_PACKAGES) {
            if (name.startsWith(pkg)) {
                return true;
            }
        }

        // ✅ 检查共享 API 包（委派给 SharedApiClassLoader）
        for (String pkg : sharedApiPackages) {
            if (name.startsWith(pkg)) {
                return true;
            }
        }

        // ✅ 检查动态添加的白名单
        for (String pkg : additionalParentPackages) {
            if (name.startsWith(pkg)) {
                return true;
            }
        }

        return false;
    }

    @Override
    public String toString() {
        return String.format("PluginClassLoader[pluginId=%s, closed=%s, urls=%d]",
                pluginId, closed, getURLs().length);
    }
}