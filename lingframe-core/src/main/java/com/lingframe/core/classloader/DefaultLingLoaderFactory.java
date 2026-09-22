package com.lingframe.core.classloader;

import com.lingframe.core.spi.LingLoaderFactory;
import com.lingframe.core.exception.ClassLoaderException;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * 默认灵元加载器工厂
 * 职责：创建灵元专用的 ClassLoader，三层类加载结构
 * <p>
 * 类加载层级：
 *
 * <pre>
 * 灵核 ClassLoader
 *     ↓ parent
 * 共享 API 层的 `SharedApiClassLoader`
 *     ↓ parent
 * 灵元实现层的 `LingClassLoader`
 * </pre>
 */
@Slf4j
public class DefaultLingLoaderFactory implements LingLoaderFactory {

    @Override
    public ClassLoader create(String lingId, File sourceFile, ClassLoader lingCoreClassLoader) {
        try {
            URL[] urls = resolveUrls(sourceFile);

            // 确定灵元 ClassLoader 的 parent
            ClassLoader parent = determineParent(lingCoreClassLoader);

            // ✅ 创建灵元 ClassLoader
            LingClassLoader lingCL = new LingClassLoader(lingId, urls, parent);
            log.debug("[{}] Creating LingClassLoader, parent={}", lingId, parent);

            return lingCL;
        } catch (MalformedURLException e) {
            throw new ClassLoaderException(lingId, sourceFile.getPath(), "Failed to create LingClassLoader", e);
        }
    }

    /**
     * 确定灵元 ClassLoader 的 parent
     * 如果启用了三层结构，使用 SharedApiClassLoader 作为 parent
     */
    private ClassLoader determineParent(ClassLoader lingCoreClassLoader) {
        // 三层结构：灵元 CL -> SharedApi CL -> 灵核 CL
        return SharedApiClassLoader.getInstance(lingCoreClassLoader);
    }

    /**
     * 解析源文件 URL
     * <p>
     * 开发模式（classes 目录）下，除了灵元自身的 classes 目录，
     * 还需扫描同级 {@code dependency/*.jar} 目录纳入灵元的第三方依赖。
     * 否则灵元引用灵核未提供的第三方库（如 Guava/Netty/POI）时，
     * 会因 ClassLoader URL 列表不含这些 jar 而抛 {@code NoClassDefFoundError}。
     * 该目录由灵元 pom 的 {@code maven-dependency-plugin} 在 compile 阶段生成。
     */
    private URL[] resolveUrls(File sourceFile) throws MalformedURLException {
        if (sourceFile.isDirectory()) {
            // 开发模式：classes 目录 + 同级 dependency/*.jar（第三方依赖）
            List<URL> urls = new ArrayList<>();
            urls.add(sourceFile.toURI().toURL());
            File depDir = new File(sourceFile.getParentFile(), "dependency");
            if (depDir.isDirectory()) {
                File[] jars = depDir.listFiles((d, n) -> n.endsWith(".jar"));
                if (jars != null) {
                    for (File jar : jars) {
                        urls.add(jar.toURI().toURL());
                    }
                    log.debug("[classes-mode] Added {} third-party dependency jar(s) from {}",
                            jars.length, depDir.getAbsolutePath());
                }
            }
            return urls.toArray(new URL[0]);
        } else if (sourceFile.getName().endsWith(".jar")) {
            // 生产模式：JAR 包（应为 fat jar，含依赖）
            return new URL[] { sourceFile.toURI().toURL() };
        } else {
            throw new ClassLoaderException(null, sourceFile.getPath(), "Unsupported source file type: " + sourceFile);
        }
    }
}
