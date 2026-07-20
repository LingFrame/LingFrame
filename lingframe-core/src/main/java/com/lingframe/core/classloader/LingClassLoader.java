package com.lingframe.core.classloader;

import com.lingframe.core.exception.ClassLoaderException;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLConnection;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 灵元类加载器。
 * 特性：
 * 1. Child-First（优先加载灵元内部类）
 * 2. 强制委派白名单（Core API / Shared API 必须走父加载器）
 * 3. 资源加载 Child-First（防止误读到灵核配置）
 * 4. 安全关闭（防止关闭后继续被误用）
 * <p>
 * ⚠️ 这里的共享 API ClassLoader 绑定和额外父委派包，不是"普通运行时配置"，而是类加载边界本身。
 * 一旦灵元开始装载实现类，就必须冻结这条边界；否则同名类可能在不同时间走出不同的委派路径，
 * 最终演变成最难排查的 ClassCastException / LinkageError。
 * <p>
 * <b>JDK 17+ 卸载前提条件</b>：
 * <ul>
 *   <li>JDK 17 默认强封装内部 API，灵元卸载时 {@code ThreadReferenceUnloadHook} 通过反射清理
 *       {@code ThreadLocal}/{@code ResourceBundle} 等持有灵元 ClassLoader 引用的内部缓存，
 *       需要以下 JVM 参数开放访问：
 *       <pre>
 *       --add-opens java.base/java.lang=ALL-UNNAMED
 *       --add-opens java.base/java.lang.reflect=ALL-UNNAMED
 *       --add-opens java.base/java.util=ALL-UNNAMED
 *       --add-opens java.base/sun.nio.ch=ALL-UNNAMED
 *       </pre>
 *   </li>
 *   <li>缺少上述参数时，卸载仍可执行但反射清理会静默失败，
 *       可能导致灵元 ClassLoader 无法被 GC 回收（Metaspace 泄漏）</li>
 *   <li>JDK 8 无需任何额外参数</li>
 * </ul>
 */
@Slf4j
public class LingClassLoader extends URLClassLoader {

    // 必须强制走父加载器的包（JDK 基础 + 灵珑自身依赖 + 契约包）
    // 边界约束：core 只持「灵珑自身必须委派」的包——JDK 基础、灵珑 API 契约、
    // 灵珑自身用的门面（slf4j/lombok/snakeyaml）。生态环境包（Spring/Jackson/Logback/Log4j2）
    // 不应在此，由 runtime 适配层经 addParentDelegatePackages 注入，避免 core 替灵核决策。
    private static final List<String> FORCE_PARENT_PACKAGES = Arrays.asList(
            "java.", "javax.", "jakarta.", "jdk.", "sun.", "com.sun.", "org.w3c.", "org.xml.",
            "com.lingframe.api.", // API 契约必须共享
            "lombok.", // 灵珑自身用 Lombok @Slf4j 等注解，门面共享
            "org.slf4j.", // 灵珑自身日志门面，门面共享
            "org.yaml.snakeyaml." // 灵珑自身用 snakeyaml 解析 ling.yml，门面共享
    );

    // 共享 API ClassLoader 引用：启动期由 SharedApiManager 绑定，按完整类名精确判定是否为公共契约
    // ⚠️ 不再用包前缀 startsWith，避免同包的灵元内部类被误判为公共契约
    private static volatile SharedApiClassLoader sharedApiClassLoader;

    // 可配置的额外委派包列表
    private static final List<String> additionalParentPackages = new CopyOnWriteArrayList<>();

    // 🔒 共享 API 边界一旦冻结，委派规则就不允许再变动，防止“半新半旧”的类加载结果混在同一进程里
    private static final AtomicBoolean SHARED_API_BOUNDARY_FROZEN = new AtomicBoolean(false);

    // ==================== 实例状态 ====================

    private final String lingId;
    private volatile boolean closed = false;

    // 存活实例计数器（构造时递增，close时递减），用于监控对照卸载真实情况
    private static final AtomicLong ALIVE_COUNT = new AtomicLong(0);

    /**
     * 获取当前存活的 LingClassLoader 数量
     */
    public static long getAliveCount() {
        return ALIVE_COUNT.get();
    }

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
            // 为兼容 JDK 8：该版本没有 setDefaultUseCaches(String protocol, boolean defaultVal)
            // 必须创建一个真实的 jar URL 连接实例来关闭整个 JVM 级别的 jar 缓存默认值
            URLConnection connection = new URL("jar:file://dummy.jar!/").openConnection();
            connection.setDefaultUseCaches(false);
        } catch (Throwable t) {
            log.warn("Failed to set default use caches to false for 'jar' protocol", t);
        }

        log.debug("[{}] ClassLoader created with {} URLs", lingId, urls.length);
        ALIVE_COUNT.incrementAndGet();
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
     * 绑定共享 API ClassLoader。
     * 绑定后，LingClassLoader 通过 isSharedClass 按完整类名精确判定是否为公共契约，
     * 不再使用包前缀推断，避免同包的灵元内部类被误判为公共契约。
     *
     * @param sharedApiClassLoader 共享 API ClassLoader 实例
     */
    public static void bindSharedApiClassLoader(SharedApiClassLoader sharedApiClassLoader) {
        ensureSharedBoundaryMutable("bind SharedApiClassLoader");
        SharedApiClassLoader old = LingClassLoader.sharedApiClassLoader;
        LingClassLoader.sharedApiClassLoader = sharedApiClassLoader;
        // 仅在引用真正变化时打日志，避免 addApi 重复调用时刷屏
        if (sharedApiClassLoader != null && sharedApiClassLoader != old) {
            log.info("📦 [SharedApi] Bound SharedApiClassLoader, shared classes: {}",
                    sharedApiClassLoader.getSharedClassCount());
        }
    }

    /**
     * 解绑共享 API ClassLoader。仅用于关闭阶段或测试重置。
     */
    public static void unbindSharedApiClassLoader() {
        ensureSharedBoundaryMutable("unbind SharedApiClassLoader");
        sharedApiClassLoader = null;
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
        sharedApiClassLoader = null;
        additionalParentPackages.clear();
    }

    private static void ensureSharedBoundaryMutable(String action) {
        // ⚠️ 如果允许在运行期继续改委派规则，同一个 ClassLoader 里的“已加载类”和“未加载类”
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

            // 强制父委派包：独占委派，父未找到则确定性失败，禁止子加载器定义同名类型
            // （避免 com.lingframe.api.* / java.* 等被灵元 JAR 伪造导致类型分裂）
            if (shouldDelegateToParent(name)) {
                try {
                    ClassLoader parent = getParent();
                    if (parent != null) {
                        c = parent.loadClass(name);
                    } else {
                        // parent==null 表示仅 bootstrap 链路：必须从 bootstrap 解析
                        c = Class.forName(name, false, null);
                    }
                } catch (ClassNotFoundException e) {
                    throw new ClassNotFoundException(
                            "Forced parent-delegate class not found in parent loader: " + name, e);
                }
                if (c == null) {
                    throw new ClassNotFoundException(
                            "Forced parent-delegate class resolved to null: " + name);
                }
                if (resolve) {
                    resolveClass(c);
                }
                return c;
            }

            // 子优先：优先从当前类加载器加载
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
        ALIVE_COUNT.decrementAndGet();
        log.info("[{}] Closing ClassLoader...", lingId);

        // super.close() 可能抛异常，但缓存清理必须执行，用 try-finally 保证
        try {
            // 调用父类的 close() 释放 JAR 文件句柄
            super.close();
            log.info("[{}] ClassLoader closed successfully", lingId);
        } catch (IOException e) {
            log.error("[{}] Error closing ClassLoader", lingId, e);
            throw e;
        } finally {
            // 🔥 清理 URLClassPath 内部缓存（loaders、path 等）
            // `super.close()` 已关闭文件句柄，但某些 JVM 实现可能在 `URLClassPath` 中残留引用。
            // 无论 super.close() 是否异常，都需清理，避免缓存残留导致泄漏。
            cleanupInternalCaches();
        }
        // 💡 不再在此处调用 System.gc()
        // 垃圾回收提示由 `ThreadReferenceUnloadHook` 在所有清理完成后统一触发，
        // 此处调用没有实际效果（引用链尚未完全切断）
    }

    /**
     * 清理 URLClassLoader 内部缓存
     * <p>
     * 委托给 {@link ClassLoaderCleanupUtil} 统一处理，
     * 避免与 {@link SharedApiClassLoader} 维护两份相同的反射逻辑。
     */
    private void cleanupInternalCaches() {
        ClassLoaderCleanupUtil.cleanupUrlClassPath(this, "[" + lingId + "]");
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

        // 检查共享 API（按完整类名精确判定：只有 SharedApiClassLoader 实际登记的类才是公共契约）
        // ⚠️ 不再用包前缀 startsWith，避免同包的灵元内部类（如 OrderDTO）被误判为公共契约
        SharedApiClassLoader sac = sharedApiClassLoader;
        if (sac != null && sac.isSharedClass(name)) {
            return true;
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
