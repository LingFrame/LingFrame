package com.lingframe.runtime.adapter;

import com.lingframe.api.annotation.LingService;
import com.lingframe.api.context.LingContext;
import com.lingframe.api.ling.Ling;
import com.lingframe.core.config.LingFrameInfo;
import com.lingframe.core.context.DefaultLingContext;
import com.lingframe.core.ling.BusinessInterfaceFilter;
import com.lingframe.core.ling.LingServiceRegistrar;
import com.lingframe.core.ling.LingServiceRegistry;
import com.lingframe.core.spi.LingContainer;
import com.lingframe.api.exception.InvalidArgumentException;
import com.lingframe.core.exception.LingInstallException;
import com.lingframe.api.exception.LingRuntimeException;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * 纯 Java 灵元容器
 * 不依赖 Spring，直接反射调用生命周期方法
 */
@Slf4j
public class NativeLingContainer implements LingContainer {

    private final String lingId;
    private Ling lingInstance; // 非 final，以便在 stop() 中清除
    private ClassLoader classLoader; // 非 final，以便在 stop() 中清除

    private final File sourceFile; // 源码/Jar路径，用于扫描

    private LingContext savedContext;
    private volatile boolean active = false;

    /**
     * 灵核只读配置门面（可选）。替代 {@code LingFrameConfig.current()} 静态穿透。
     * 未注入时隐式注册开关兜底 true（与 builder 默认一致）。
     */
    private LingFrameInfo lingFrameInfo;

    // 简易 Bean 容器：Class -> Instance
    private final Map<Class<?>, Object> singletons = new ConcurrentHashMap<>();

    public NativeLingContainer(String lingId, Class<?> mainClass,
            ClassLoader classLoader, File sourceFile) {
        this(lingId, mainClass, classLoader, sourceFile, null);
    }

    public NativeLingContainer(String lingId, Class<?> mainClass,
            ClassLoader classLoader, File sourceFile, LingFrameInfo lingFrameInfo) {
        this.lingId = lingId;
        this.classLoader = classLoader;
        this.sourceFile = sourceFile;
        this.lingFrameInfo = lingFrameInfo;
        try {
            // 强校验：Native 模式下，主类必须实现 Ling 接口
            if (!Ling.class.isAssignableFrom(mainClass)) {
                throw new InvalidArgumentException("mainClass",
                        "Native ling main class must implement Ling: " + mainClass.getName());
            }
            // 实例化灵元入口类并放入单例池
            this.lingInstance = (Ling) mainClass.getDeclaredConstructor().newInstance();
            this.singletons.put(mainClass, lingInstance);
        } catch (Exception e) {
            throw new LingInstallException(lingId, "Failed to create native ling instance", e);
        }
    }

    /**
     * 注入灵核只读配置（装配后可选调用）。
     */
    public void setLingFrameInfo(LingFrameInfo lingFrameInfo) {
        this.lingFrameInfo = lingFrameInfo;
    }

    @Override
    public void start(LingContext context) {
        this.savedContext = context;
        Thread t = Thread.currentThread();
        ClassLoader old = t.getContextClassLoader();
        try {
            t.setContextClassLoader(classLoader);
            log.info("Starting Native ling: {}", context.getLingId());

            // 核心：调用灵元的 onStart
            lingInstance.onStart(context);
            this.active = true;

            // 🔥 核心：扫描并注册服务
            scanAndRegisterServices(context);
        } catch (Exception e) {
            this.active = false;
            throw new LingInstallException(lingId, "Failed to start native ling", e);
        } finally {
            t.setContextClassLoader(old);
        }
    }

    @Override
    public void stop() {
        if (!active)
            return;
        log.info("[{}] Stopping native ling...", lingId);
        try {
            lingInstance.onStop(savedContext);
        } catch (Exception e) {
            log.error("[{}] Error during stop", lingId, e);
        }
        // 清理单例池，帮助 GC
        singletons.clear();

        // 🔥 关键：清除对 ClassLoader 等的引用，防止泄漏
        this.savedContext = null;
        this.lingInstance = null;
        this.classLoader = null;

        active = false;
    }

    // ==================== 服务扫描与注册逻辑（委派给统一注册器） ====================

    private void scanAndRegisterServices(LingContext context) {
        if (!(context instanceof DefaultLingContext)) {
            log.warn("[{}] Context is not DefaultLingContext, skipping service registration.", lingId);
            return;
        }
        DefaultLingContext coreCtx = (DefaultLingContext) context;
        LingServiceRegistry registry = coreCtx.getLingServiceRegistry();
        // native 路径无生态环境前缀，仅持 core 默认排除 + 用户排除项（暂无配置透传入口，用空集）
        BusinessInterfaceFilter interfaceFilter = BusinessInterfaceFilter.coreDefaults();
        // 注入式读取隐式注册开关，禁止热路径静态穿透 LingFrameConfig.current()
        boolean implicitRegistration = lingFrameInfo == null || lingFrameInfo.isImplicitRegistration();
        LingServiceRegistrar registrar = new LingServiceRegistrar(
                registry, interfaceFilter, implicitRegistration, coreCtx);

        log.info("[{}] Registering services via registrar...", lingId);
        Set<Class<?>> classes = scanClasses();

        // 🔥 委派给统一注册器：显式 @LingService + 隐式接口一并处理。
        // 健壮性：仅对「持 @LingService 方法 或 实现业务接口」的类走 singletons 实例化，
        // 避免误试实例化测试用内部类（如 MinimalLingContext 无空构造会炸）。
        // 主类 lingInstance 始终注册——它是灵元入口，即便无 @LingService 也可能有隐式接口。
        registrar.register(lingId, lingInstance, lingInstance.getClass());

        for (Class<?> clazz : classes) {
            // 只扫普通类
            if (clazz.isInterface() || Modifier.isAbstract(clazz.getModifiers())) {
                continue;
            }
            // 跳过主类（上面已注册）
            if (clazz == lingInstance.getClass()) {
                continue;
            }
            // 跳过无 @LingService 方法且不实现业务接口的纯辅助类——不注册也不实例化
            if (!hasLingServiceMethod(clazz) && !implementsBusinessInterface(clazz, interfaceFilter)) {
                continue;
            }
            Object instance = singletons.computeIfAbsent(clazz, k -> {
                try {
                    return k.getDeclaredConstructor().newInstance();
                } catch (Exception e) {
                    // 健壮性：实例化失败（私有构造/抛异常等）记日志跳过，不拖垮整个灵元启动。
                    // 等价契约：原 registerService 对无法实例化的类记日志不崩，此处同。
                    log.warn("[{}] Failed to create bean for {} (skipping registration): {}",
                            lingId, k.getName(), e.getMessage());
                    return null;
                }
            });
            if (instance == null) {
                continue; // 实例化失败，跳过该类
            }
            registrar.register(lingId, instance, clazz);
        }
    }

    /** 判定类是否持 @LingService 方法（TYPE 级或 METHOD 级） */
    private boolean hasLingServiceMethod(Class<?> clazz) {
        if (clazz.getAnnotation(LingService.class) != null) {
            return true;
        }
        for (java.lang.reflect.Method m : clazz.getMethods()) {
            if (m.getAnnotation(LingService.class) != null) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判定类是否实现至少一个业务接口（经 BusinessInterfaceFilter 过滤后）。
     * 递归遍历父类链，与 LingServiceRegistrar.collectBusinessInterfaces 的递归收集语义一致，
     * 避免辅助类通过父类继承的业务接口被预筛误跳过导致漏注册。
     */
    private boolean implementsBusinessInterface(Class<?> clazz, BusinessInterfaceFilter filter) {
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            for (Class<?> iface : current.getInterfaces()) {
                if (filter.isBusinessInterface(iface)) {
                    return true;
                }
            }
            current = current.getSuperclass();
        }
        return false;
    }

    // ==================== 类扫描实现 (File/Jar) ====================

    private Set<Class<?>> scanClasses() {
        Set<Class<?>> classes = new HashSet<>();
        try {
            if (sourceFile.isDirectory()) {
                scanDir(sourceFile.toPath(), classes);
            } else if (sourceFile.getName().endsWith(".jar")) {
                scanJar(sourceFile, classes);
            }
        } catch (Exception e) {
            log.warn("[{}] Class scanning failed: {}", lingId, e.getMessage());
        }
        return classes;
    }

    private void scanDir(Path root, Set<Class<?>> classes) throws IOException {
        try (Stream<Path> stream = Files.walk(root)) {
            stream.filter(p -> p.toString().endsWith(".class"))
                    .forEach(p -> {
                        String className = toClassName(root, p);
                        loadClassSafely(className).ifPresent(classes::add);
                    });
        }
    }

    private void scanJar(File jarFile, Set<Class<?>> classes) throws IOException {
        try (JarFile jar = new JarFile(jarFile)) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.getName().endsWith(".class") && !entry.isDirectory()) {
                    String className = entry.getName().replace('/', '.').replace(".class", "");
                    loadClassSafely(className).ifPresent(classes::add);
                }
            }
        }
    }

    private String toClassName(Path root, Path file) {
        return root.relativize(file).toString()
                .replace(File.separatorChar, '.')
                .replace(".class", "");
    }

    private Optional<Class<?>> loadClassSafely(String className) {
        try {
            // 排除 unit-info 和 package-info
            if (className.equals("unit-info") || className.endsWith("package-info"))
                return Optional.empty();
            return Optional.of(classLoader.loadClass(className));
        } catch (Throwable e) {
            // 忽略 NoClassDefFoundError 等，因为灵元可能依赖了 provided 的库但还没加载
            return Optional.empty();
        }
    }

    @Override
    public boolean isActive() {
        return active;
    }

    @Override
    public <T> T getBean(Class<T> type) {
        // Native 容器没有 IOC 容器，只支持返回灵元主类实例
        if (type.isInstance(lingInstance)) {
            return type.cast(lingInstance);
        }
        return null;
    }

    @Override
    public Object getBean(String beanName) {
        if (beanName != null && lingInstance != null) {
            try {
                ClassLoader cl = lingInstance.getClass().getClassLoader();
                if (cl != null) {
                    Class<?> targetClass = cl.loadClass(beanName);
                    if (targetClass.isInstance(lingInstance)) {
                        return lingInstance;
                    }
                }
            } catch (ClassNotFoundException ignored) {
            }
            if (beanName.equals(lingId) || lingId.endsWith(":" + beanName)) {
                return lingInstance;
            }
            return lingInstance;
        }
        return null;
    }

    @Override
    public String[] getBeanNames() {
        return new String[0];
    }

    @Override
    public ClassLoader getClassLoader() {
        return classLoader;
    }
}

