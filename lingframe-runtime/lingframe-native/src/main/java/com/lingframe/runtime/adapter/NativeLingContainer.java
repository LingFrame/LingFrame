package com.lingframe.runtime.adapter;

import com.lingframe.api.annotation.LingService;
import com.lingframe.api.context.LingContext;
import com.lingframe.api.ling.Ling;
import com.lingframe.core.context.CoreLingContext;
import com.lingframe.core.spi.LingContainer;
import com.lingframe.api.exception.InvalidArgumentException;
import com.lingframe.core.exception.LingInstallException;
import com.lingframe.api.exception.LingRuntimeException;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
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

    // 简易 Bean 容器：Class -> Instance
    private final Map<Class<?>, Object> singletons = new ConcurrentHashMap<>();

    public NativeLingContainer(String lingId, Class<?> mainClass,
            ClassLoader classLoader, File sourceFile) {
        this.lingId = lingId;
        this.classLoader = classLoader;
        this.sourceFile = sourceFile;
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

    // ==================== 服务扫描与注册逻辑 ====================

    private void scanAndRegisterServices(LingContext context) {
        CoreLingContext coreCtx = null;
        if (!(context instanceof CoreLingContext)) {
            coreCtx = (CoreLingContext) context;
            log.warn("[{}] Context is not CoreLingContext, skipping service registration.", lingId);
            return;
        }

        log.info("[{}] Scanning for @LingService...", lingId);
        Set<Class<?>> classes = scanClasses();

        for (Class<?> clazz : classes) {
            // 只扫描普通类
            if (clazz.isInterface() || Modifier.isAbstract(clazz.getModifiers()))
                continue;

            // 检查方法上的注解
            for (Method method : clazz.getMethods()) {
                LingService annotation = method.getAnnotation(LingService.class);
                if (annotation != null) {
                    registerService(coreCtx, clazz, method, annotation);
                }
            }
        }
    }

    private void registerService(CoreLingContext ctx, Class<?> clazz, Method method, LingService annotation) {
        try {
            // 获取或创建单例
            Object instance = singletons.computeIfAbsent(clazz, k -> {
                try {
                    return k.getDeclaredConstructor().newInstance();
                } catch (Exception e) {
                    throw new LingRuntimeException(lingId, "Failed to create bean for " + k.getName(), e);
                }
            });

            String fqsid = lingId + ":" + annotation.id();
            ctx.registerProtocolService(fqsid, instance, method);
            log.debug("[{}] Registered native service: {}", lingId, fqsid);
        } catch (Exception e) {
            log.error("[{}] Failed to register service: {}", lingId, method.getName(), e);
        }
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
        return null; // 不支持按名查找
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