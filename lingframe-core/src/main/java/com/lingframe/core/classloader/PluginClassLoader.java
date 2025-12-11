package com.lingframe.core.classloader;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.*;

/**
 * 生产级插件类加载器
 * 特性：
 * 1. Child-First (优先加载插件内部类)
 * 2. 强制委派白名单 (Core API 必须走父加载器)
 * 3. 资源加载 Child-First (防止读取到宿主的配置)
 */
@Slf4j
public class PluginClassLoader extends URLClassLoader {

    // 必须强制走父加载器的包（契约包 + JDK）
    private static final List<String> FORCE_PARENT_PACKAGES = Arrays.asList(
            "java.", "javax.", "jdk.", "sun.", "com.sun.", "org.w3c.", "org.xml.",
            "com.lingframe.api.", // API 契约必须共享
            "org.slf4j.",         // 日志门面通常共享
            "org.apache.logging.log4j.", // Log4j2
            "ch.qos.logback.",    // Logback
            "org.springframework.", // Spring框架相关类
            "com.fasterxml.jackson.", // Jackson JSON处理
            "org.yaml.snakeyaml." // SnakeYAML
    );

    // 可配置的额外委派包列表
    private static volatile List<String> additionalParentPackages = new ArrayList<>();

    public PluginClassLoader(URL[] urls, ClassLoader parent) {
        super(urls, parent);
    }

    /**
     * 添加额外的强制委派包
     * @param packages 包名前缀列表
     */
    public static void addAdditionalParentPackages(List<String> packages) {
        additionalParentPackages.addAll(packages);
    }

    @Override
    public Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            // 1. 检查缓存
            Class<?> c = findLoadedClass(name);
            if (c != null) return c;

            // 2. 白名单强制委派给父加载器 (防止 ClassCastException)
            if (shouldDelegateToParent(name)) {
                try {
                    c = getParent().loadClass(name);
                } catch (ClassNotFoundException ignored) {
                }
            }

            // 3. Child-First: 优先自己加载
            if (c == null) {
                try {
                    c = findClass(name);
                } catch (ClassNotFoundException ignored) {
                }
            }

            // 4. 兜底: 自己没有，再找父亲 (加载公共库如 StringUtils)
            if (c == null) {
                c = super.loadClass(name, resolve);
            }

            if (resolve) resolveClass(c);
            return c;
        }
    }

    @Override
    public URL getResource(String name) {
        // 资源加载也必须 Child-First，否则会读到宿主的 application.properties
        URL url = findResource(name);
        if (url != null) return url;
        return super.getResource(name);
    }

    @Override
    public Enumeration<URL> getResources(String name) throws IOException {
        // 组合资源：自己的 + 父加载器的
        Enumeration<URL> localUrls = findResources(name);
        Enumeration<URL> parentUrls = null;
        if (getParent() != null) {
            parentUrls = getParent().getResources(name);
        }

        List<URL> urls = new ArrayList<>();
        while (localUrls.hasMoreElements()) urls.add(localUrls.nextElement());
        if (parentUrls != null) {
            while (parentUrls.hasMoreElements()) urls.add(parentUrls.nextElement());
        }
        return Collections.enumeration(urls);
    }

    private boolean shouldDelegateToParent(String name) {
        for (String pkg : FORCE_PARENT_PACKAGES) {
            if (name.startsWith(pkg)) return true;
        }
        return false;
    }
}