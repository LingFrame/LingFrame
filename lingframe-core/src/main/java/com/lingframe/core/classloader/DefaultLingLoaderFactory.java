package com.lingframe.core.classloader;

import com.lingframe.core.spi.LingLoaderFactory;
import com.lingframe.core.exception.ClassLoaderException;
import com.lingframe.api.exception.InvalidArgumentException;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;

/**
 * 默认单元加载器工厂
 * 职责：创建单元专用的 ClassLoader，三层类加载结构
 * <p>
 * 类加载层级：
 *
 * <pre>
 * 灵核 ClassLoader
 *     ↓ parent
 * SharedApiClassLoader (共享 API 层)
 *     ↓ parent
 * LingClassLoader (单元实现层)
 * </pre>
 */
@Slf4j
public class DefaultLingLoaderFactory implements LingLoaderFactory {

    @Override
    public ClassLoader create(String lingId, File sourceFile, ClassLoader hostClassLoader) {
        try {
            URL[] urls = resolveUrls(sourceFile);

            // 确定单元 ClassLoader 的 parent
            ClassLoader parent = determineParent(hostClassLoader);

            // ✅ 创建单元 ClassLoader
            LingClassLoader lingCL = new LingClassLoader(lingId, urls, parent);
            log.debug("[{}] Creating LingClassLoader, parent={}", lingId, parent);

            return lingCL;
        } catch (MalformedURLException e) {
            throw new ClassLoaderException(lingId, sourceFile.getPath(), "创建 LingClassLoader 失败", e);
        }
    }

    /**
     * 确定单元 ClassLoader 的 parent
     * 如果启用了三层结构，使用 SharedApiClassLoader 作为 parent
     */
    private ClassLoader determineParent(ClassLoader hostClassLoader) {
        // 三层结构：单元 CL -> SharedApi CL -> 灵核 CL
        return SharedApiClassLoader.getInstance(hostClassLoader);
    }

    /**
     * 解析源文件 URL
     */
    private URL[] resolveUrls(File sourceFile) throws MalformedURLException {
        if (sourceFile.isDirectory()) {
            // 开发模式：classes 目录
            return new URL[] { sourceFile.toURI().toURL() };
        } else if (sourceFile.getName().endsWith(".jar")) {
            // 生产模式：JAR 包
            return new URL[] { sourceFile.toURI().toURL() };
        } else {
            throw new InvalidArgumentException("sourceFile", "不支持的源文件类型: " + sourceFile);
        }
    }
}