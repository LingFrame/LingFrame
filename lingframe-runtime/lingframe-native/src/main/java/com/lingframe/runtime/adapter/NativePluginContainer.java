package com.lingframe.runtime.adapter;

import com.lingframe.api.annotation.LingService;
import com.lingframe.api.context.PluginContext;
import com.lingframe.api.plugin.LingPlugin;
import com.lingframe.core.context.CorePluginContext;
import com.lingframe.core.plugin.PluginManager;
import com.lingframe.core.spi.PluginContainer;
import com.lingframe.api.exception.InvalidArgumentException;
import com.lingframe.core.exception.PluginInstallException;
import com.lingframe.core.exception.PluginRuntimeException;
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
 * 纯 Java 插件容器
 * 不依赖 Spring，直接反射调用生命周期方法
 */
@Slf4j
public class NativePluginContainer implements PluginContainer {

    private final String pluginId;
    private final LingPlugin pluginInstance;
    private final ClassLoader classLoader;

    private final File sourceFile; // 源码/Jar路径，用于扫描

    private PluginContext savedContext;
    private volatile boolean active = false;

    // 简易 Bean 容器：Class -> Instance
    private final Map<Class<?>, Object> singletons = new ConcurrentHashMap<>();

    public NativePluginContainer(String pluginId, Class<?> mainClass,
            ClassLoader classLoader, File sourceFile) {
        this.pluginId = pluginId;
        this.classLoader = classLoader;
        this.sourceFile = sourceFile;
        try {
            // 强校验：Native 模式下，主类必须实现 LingPlugin 接口
            if (!LingPlugin.class.isAssignableFrom(mainClass)) {
                throw new InvalidArgumentException("mainClass",
                        "Native plugin main class must implement LingPlugin: " + mainClass.getName());
            }
            // 实例化插件入口类并放入单例池
            this.pluginInstance = (LingPlugin) mainClass.getDeclaredConstructor().newInstance();
            this.singletons.put(mainClass, pluginInstance);
        } catch (Exception e) {
            throw new PluginInstallException(pluginId, "Failed to create native plugin instance", e);
        }
    }

    @Override
    public void start(PluginContext context) {
        this.savedContext = context;
        Thread t = Thread.currentThread();
        ClassLoader old = t.getContextClassLoader();
        try {
            t.setContextClassLoader(classLoader);
            log.info("Starting Native Plugin: {}", context.getPluginId());

            // 核心：调用插件的 onStart
            pluginInstance.onStart(context);
            this.active = true;

            // 🔥 核心：扫描并注册服务
            scanAndRegisterServices(context);
        } catch (Exception e) {
            this.active = false;
            throw new PluginInstallException(pluginId, "Failed to start native plugin", e);
        } finally {
            t.setContextClassLoader(old);
        }
    }

    @Override
    public void stop() {
        if (!active)
            return;
        log.info("[{}] Stopping native plugin...", pluginId);
        try {
            pluginInstance.onStop(savedContext);
        } catch (Exception e) {
            log.error("[{}] Error during stop", pluginId, e);
        }
        // 清理单例池，帮助 GC
        singletons.clear();
        active = false;
    }

    // ==================== 服务扫描与注册逻辑 ====================

    private void scanAndRegisterServices(PluginContext context) {
        if (!(context instanceof CorePluginContext coreCtx)) {
            log.warn("[{}] Context is not CorePluginContext, skipping service registration.", pluginId);
            return;
        }
        PluginManager pluginManager = coreCtx.getPluginManager();

        log.info("[{}] Scanning for @LingService...", pluginId);
        Set<Class<?>> classes = scanClasses();

        for (Class<?> clazz : classes) {
            // 只扫描普通类
            if (clazz.isInterface() || Modifier.isAbstract(clazz.getModifiers()))
                continue;

            // 检查方法上的注解
            for (Method method : clazz.getMethods()) {
                LingService annotation = method.getAnnotation(LingService.class);
                if (annotation != null) {
                    registerService(pluginManager, clazz, method, annotation);
                }
            }
        }
    }

    private void registerService(PluginManager pm, Class<?> clazz, Method method, LingService annotation) {
        try {
            // 获取或创建单例
            Object instance = singletons.computeIfAbsent(clazz, k -> {
                try {
                    return k.getDeclaredConstructor().newInstance();
                } catch (Exception e) {
                    throw new PluginRuntimeException(pluginId, "Failed to create bean for " + k.getName(), e);
                }
            });

            String fqsid = pluginId + ":" + annotation.id();
            pm.registerProtocolService(pluginId, fqsid, instance, method);
            log.debug("[{}] Registered native service: {}", pluginId, fqsid);
        } catch (Exception e) {
            log.error("[{}] Failed to register service: {}", pluginId, method.getName(), e);
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
            log.warn("[{}] Class scanning failed: {}", pluginId, e.getMessage());
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
            // 排除 module-info 和 package-info
            if (className.equals("module-info") || className.endsWith("package-info"))
                return Optional.empty();
            return Optional.of(classLoader.loadClass(className));
        } catch (Throwable e) {
            // 忽略 NoClassDefFoundError 等，因为插件可能依赖了 provided 的库但还没加载
            return Optional.empty();
        }
    }

    @Override
    public boolean isActive() {
        return active;
    }

    @Override
    public <T> T getBean(Class<T> type) {
        // Native 容器没有 IOC 容器，只支持返回插件主类实例
        if (type.isInstance(pluginInstance)) {
            return type.cast(pluginInstance);
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