package com.lingframe.core.classloader;

import com.lingframe.core.exception.ClassLoaderException;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLConnection;
import java.util.*;
import java.util.jar.JarFile;
import java.util.zip.ZipFile;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 灵元类加载器。
 * 特性：
 * 1. Child-First（优先加载灵元内部类）
 * 2. 强制委派白名单（Core API / Shared API 必须走父加载器）
 * 3. 资源加载 Child-First（防止误读到灵核配置）
 * 4. 安全关闭（防止关闭后继续被误用）
 * <p>
 * ⚠️ 这里的共享 API 包前缀和额外父委派包，不是“普通运行时配置”，而是类加载边界本身。
 * 一旦灵元开始装载实现类，就必须冻结这条边界；否则同名类可能在不同时间走出不同的委派路径，
 * 最终演变成最难排查的 ClassCastException / LinkageError。
 */
@Slf4j
public class LingClassLoader extends URLClassLoader {

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

    // 共享 API 包前缀（可动态添加，最终委派给 SharedApiClassLoader）
    private static final List<String> sharedApiPackages = new CopyOnWriteArrayList<>();

    // 可配置的额外委派包列表
    private static final List<String> additionalParentPackages = new CopyOnWriteArrayList<>();

    // 🔒 共享 API 边界一旦冻结，委派规则就不允许再变动，防止“半新半旧”的类加载结果混在同一进程里
    private static final AtomicBoolean SHARED_API_BOUNDARY_FROZEN = new AtomicBoolean(false);

    // ==================== 实例状态 ====================

    private final String lingId;
    private volatile boolean closed = false;

    public LingClassLoader(URL[] urls, ClassLoader parent) {
        this("unknown", urls, parent);
    }

    public LingClassLoader(String lingId, URL[] urls, ClassLoader parent) {
        super(urls, parent);
        this.lingId = lingId;

        // 🔥 关键修复：关闭 URLConnection 的缓存机制
        // 在 Windows 平台上，如果底层 JarURLConnection 启用了缓存，
        // 即便调用了 URLClassLoader.close()，文件句柄依然可能被 JVM 占用，导致无法覆盖重装。
        try {
            // JDK 8 兼容写法：由于 JDK 8 没有 setDefaultUseCaches(String protocol, boolean
            // defaultVal)
            // 必须创建一个真实的 jar URL 连接实例来关闭整个 JVM 级别的 jar 缓存默认值
            URLConnection connection = new URL("jar:file://dummy.jar!/").openConnection();
            connection.setDefaultUseCaches(false);
        } catch (Throwable t) {
            log.warn("Failed to set default use caches to false for 'jar' protocol", t);
        }

        log.debug("[{}] ClassLoader created with {} URLs", lingId, urls.length);
    }

    /**
     * 添加额外的强制委派包（全局生效）
     *
     * @param packages 包名前缀列表
     */
    public static void addParentDelegatePackages(Collection<String> packages) {
        if (packages != null) {
            ensureSharedBoundaryMutable("add parent delegate packages");
            additionalParentPackages.addAll(packages);
            log.info("📦 [SharedApi] Added parent delegate packages {}", packages);
        }
    }

    /**
     * 移除额外的委派包
     */
    public static void removeParentDelegatePackages(Collection<String> packages) {
        if (packages != null) {
            ensureSharedBoundaryMutable("remove parent delegate packages");
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
            ensureSharedBoundaryMutable("add shared API packages");
            sharedApiPackages.addAll(packages);
            log.info("📦 [SharedApi] Added shared API packages {}", packages);
        }
    }

    /**
     * 清空共享 API 包列表
     */
    public static void clearSharedApiPackages() {
        ensureSharedBoundaryMutable("clear shared API packages");
        sharedApiPackages.clear();
    }

    /**
     * 冻结共享 API 边界。
     * ⚠️ 冻结时机必须早于首个灵元实现类的真实装载，否则委派规则的观测结果会前后不一致。
     */
    public static void freezeSharedApiBoundary() {
        if (SHARED_API_BOUNDARY_FROZEN.compareAndSet(false, true)) {
            log.info("🔒 [SharedApi] LingClassLoader shared API boundary frozen");
        }
    }

    /**
     * 仅用于关闭阶段或测试重置。
     */
    public static void resetSharedApiBoundary() {
        SHARED_API_BOUNDARY_FROZEN.set(false);
        sharedApiPackages.clear();
        additionalParentPackages.clear();
    }

    private static void ensureSharedBoundaryMutable(String action) {
        // ⚠️ 如果允许在运行期继续改委派包前缀，同一个 ClassLoader 里的“已加载类”和“未加载类”
        // 可能从此走不同的解析路径，最后不是功能错，而是类型系统整体失真。
        if (SHARED_API_BOUNDARY_FROZEN.get()) {
            throw new IllegalStateException("Shared API boundary already frozen, cannot " + action);
        }
    }

    @Override
    public Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        // ⚠️ 关闭后的 ClassLoader 继续参与委派，会把卸载期问题拖成诡异的 NoClassDefFoundError/LinkageError
        if (closed) {
            throw new ClassLoaderException(lingId, name,
                    String.format("ClassLoader for ling [%s] has been closed, cannot load class: %s",
                            lingId, name));
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
            log.warn("[{}] Attempting to get resource from closed ClassLoader: {}", lingId, name);
            return null;
        }
        // 资源加载也必须 Child-First，否则会读到灵核的 application.properties
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
            log.debug("[{}] ClassLoader already closed", lingId);
            return;
        }

        closed = true;
        log.info("[{}] Closing ClassLoader...", lingId);

        try {
            // 调用父类的 close() 释放 JAR 文件句柄
            super.close();

            // 🔥 清理 URLClassPath 内部缓存（loaders、path 等）
            // super.close() 已关闭文件句柄，但某些 JVM 实现可能在 URLClassPath 中残留引用
            cleanupInternalCaches();

            log.info("[{}] ClassLoader closed successfully", lingId);
            // 💡 不再在此处调用 System.gc()
            // GC 提示由 BasicResourceGuard 在所有清理完成后统一触发，
            // 此处调用没有实际效果（引用链尚未完全切断）
        } catch (IOException e) {
            log.error("[{}] Error closing ClassLoader", lingId, e);
            throw e;
        }
    }

    /**
     * 清理 URLClassLoader 内部缓存
     * <p>
     * URLClassLoader 内部的 URLClassPath 可能持有已打开的 JarFile 引用和 URL 列表。
     * super.close() 会关闭文件句柄，但不一定清空集合引用，在 Windows 下会导致无法删除 JAR。
     * 此方法通过反射确保内部引用被彻底清理并强制关闭 JarFile。
     * </p>
     */
    private void cleanupInternalCaches() {
        try {
            // 获取 URLClassLoader.ucp (URLClassPath) 字段
            Field ucpField = URLClassLoader.class.getDeclaredField("ucp");
            ucpField.setAccessible(true);
            Object ucp = ucpField.get(this);

            if (ucp != null) {

                // 强制关闭所有 Loader
                try {
                    Field loadersField = ucp.getClass().getDeclaredField("loaders");
                    loadersField.setAccessible(true);
                    Object loaders = loadersField.get(ucp);
                    if (loaders instanceof List<?>) {
                        for (Object loader : (List<?>) loaders) {
                            try {
                                if (loader != null) {
                                    // 尝试获取 Loader 内部的 jar/JarFile 并关闭 (主要针对 JarLoader)
                                    try {
                                        Field jarField = loader.getClass().getDeclaredField("jar");
                                        jarField.setAccessible(true);
                                        Object jarFile = jarField.get(loader);
                                        if (jarFile instanceof JarFile) {
                                            ((JarFile) jarFile).close();
                                            log.debug("[{}] Closed JarFile via reflection", lingId);
                                        } else if (jarFile instanceof ZipFile) {
                                            ((ZipFile) jarFile).close();
                                            log.debug("[{}] Closed ZipFile via reflection", lingId);
                                        }
                                    } catch (NoSuchFieldException e) {
                                        // 忽略
                                    }
                                }
                            } catch (Exception e) {
                                log.trace("Failed to close loader internal jar", e);
                            }
                        }
                        // 清空 loaders
                        ((List<?>) loaders).clear();
                    }
                } catch (NoSuchFieldException ignored) {
                    // JVM 版本不同
                }

                // 清理 URLClassPath.path (ArrayList<URL>)
                try {
                    Field pathField = ucp.getClass().getDeclaredField("path");
                    pathField.setAccessible(true);
                    Object path = pathField.get(ucp);
                    if (path instanceof List<?>) {
                        ((List<?>) path).clear();
                    }
                } catch (NoSuchFieldException ignored) {
                    // JVM 版本不同，字段可能不存在
                }

                // 清理 URLClassPath.lmap (HashMap<String, Loader>)
                try {
                    Field lmapField = ucp.getClass().getDeclaredField("lmap");
                    lmapField.setAccessible(true);
                    Object lmap = lmapField.get(ucp);
                    if (lmap instanceof Map<?, ?>) {
                        ((Map<?, ?>) lmap).clear();
                    }
                } catch (NoSuchFieldException ignored) {
                    // JVM 版本不同
                }

                // 清理 closed (如果有这个字段的话，在一些高版本 JDK 中防止再用)
                try {
                    Field closedField = ucp.getClass().getDeclaredField("closed");
                    closedField.setAccessible(true);
                    closedField.set(ucp, true);
                } catch (NoSuchFieldException ignored) {
                }

                log.debug("[{}] URLClassPath internal caches and JAR handles cleared", lingId);
            }
        } catch (Exception e) {
            log.debug("[{}] Failed to cleanup URLClassPath: {}", lingId, e.getMessage());
        }
    }

    /**
     * 检查 ClassLoader 是否已关闭
     */
    public boolean isClosed() {
        return closed;
    }

    /**
     * 获取灵元 ID
     */
    public String getLingId() {
        return lingId;
    }

    private boolean shouldDelegateToParent(String name) {
        // 检查内置白名单
        for (String pkg : FORCE_PARENT_PACKAGES) {
            if (name.startsWith(pkg)) {
                return true;
            }
        }

        // 检查共享 API 包（委派给 SharedApiClassLoader）
        for (String pkg : sharedApiPackages) {
            if (name.startsWith(pkg)) {
                return true;
            }
        }

        // 检查动态添加的白名单
        for (String pkg : additionalParentPackages) {
            if (name.startsWith(pkg)) {
                return true;
            }
        }

        return false;
    }

    @Override
    public String toString() {
        return String.format("LingClassLoader[lingId=%s, closed=%s, urls=%d]",
                lingId, closed, getURLs().length);
    }
}
