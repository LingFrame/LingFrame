package com.lingframe.core.classloader;

import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Field;
import java.net.URLClassLoader;
import java.util.List;
import java.util.Map;
import java.util.jar.JarFile;
import java.util.zip.ZipFile;

/**
 * URLClassLoader 内部缓存清理工具。
 * <p>
 * {@code URLClassLoader.close()} 会关闭文件句柄，但不一定清空集合引用，
 * 在 Windows 下会导致无法删除 JAR 文件。
 * 此工具通过反射确保 URLClassPath 内部引用被彻底清理。
 * <p>
 * 供 {@link LingClassLoader} 和 {@link SharedApiClassLoader} 共用，
 * 避免两处维护相同的反射逻辑。
 */
@Slf4j
final class ClassLoaderCleanupUtil {

    private ClassLoaderCleanupUtil() {
    }

    /**
     * 清理 URLClassLoader 内部的 URLClassPath 缓存。
     *
     * @param loader 目标 ClassLoader
     * @param logPrefix 日志前缀（如灵元 ID 或 "[SharedApi]"）
     */
    static void cleanupUrlClassPath(URLClassLoader loader, String logPrefix) {
        try {
            Field ucpField = URLClassLoader.class.getDeclaredField("ucp");
            ucpField.setAccessible(true);
            Object ucp = ucpField.get(loader);

            if (ucp == null) {
                return;
            }

            // 强制关闭所有 Loader 内部的 JarFile
            closeLoaderJars(ucp, logPrefix);

            // 清理 URLClassPath.loaders
            clearFieldList(ucp, "loaders");

            // 清理 URLClassPath.path
            clearFieldList(ucp, "path");

            // 清理 URLClassPath.lmap
            clearFieldMap(ucp, "lmap");

            // 标记 closed（高版本 JDK）
            markUcpClosed(ucp);

            log.debug("{} URLClassPath internal caches and JAR handles cleared", logPrefix);
        } catch (Exception e) {
            log.debug("{} Failed to cleanup URLClassPath: {}", logPrefix, e.getMessage());
        }
    }

    private static void closeLoaderJars(Object ucp, String logPrefix) {
        try {
            Field loadersField = ucp.getClass().getDeclaredField("loaders");
            loadersField.setAccessible(true);
            Object loaders = loadersField.get(ucp);
            if (!(loaders instanceof List<?>)) {
                return;
            }
            for (Object loader : (List<?>) loaders) {
                if (loader == null) {
                    continue;
                }
                closeSingleLoaderJar(loader, logPrefix);
            }
        } catch (Exception e) {
            // 不同 JVM 版本的字段布局可能不同
            log.trace("{} Failed to close loader JARs: {}", logPrefix, e.getMessage());
        }
    }

    private static void closeSingleLoaderJar(Object loader, String logPrefix) {
        try {
            Field jarField = loader.getClass().getDeclaredField("jar");
            jarField.setAccessible(true);
            Object jarFile = jarField.get(loader);
            if (jarFile instanceof JarFile) {
                ((JarFile) jarFile).close();
                log.debug("{} Closed JarFile via reflection", logPrefix);
            } else if (jarFile instanceof ZipFile) {
                ((ZipFile) jarFile).close();
                log.debug("{} Closed ZipFile via reflection", logPrefix);
            }
        } catch (Exception e) {
            // 非 JarLoader 或字段不可访问
            log.trace("{} Failed to close single loader jar: {}", logPrefix, e.getMessage());
        }
    }

    private static void clearFieldList(Object target, String fieldName) {
        try {
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            Object value = field.get(target);
            if (value instanceof List<?>) {
                ((List<?>) value).clear();
            }
        } catch (Exception e) {
            // 不同 JVM 版本中该字段可能不存在或不可访问
            log.trace("Failed to clear field list '{}': {}", fieldName, e.getMessage());
        }
    }

    private static void clearFieldMap(Object target, String fieldName) {
        try {
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            Object value = field.get(target);
            if (value instanceof Map<?, ?>) {
                ((Map<?, ?>) value).clear();
            }
        } catch (Exception e) {
            // 不同 JVM 版本的字段布局可能不同
            log.trace("Failed to clear field map '{}': {}", fieldName, e.getMessage());
        }
    }

    private static void markUcpClosed(Object ucp) {
        try {
            Field closedField = ucp.getClass().getDeclaredField("closed");
            closedField.setAccessible(true);
            closedField.set(ucp, true);
        } catch (Exception e) {
            // 低版本 JDK 没有此字段
            log.trace("Failed to mark URLClassPath as closed: {}", e.getMessage());
        }
    }
}
