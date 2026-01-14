package com.lingframe.core.classloader;

import com.lingframe.core.spi.PluginLoaderFactory;
import com.lingframe.core.exception.ClassLoaderException;
import com.lingframe.api.exception.InvalidArgumentException;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;

/**
 * 默认插件加载器工厂
 * 职责：创建插件专用的 ClassLoader，三层类加载结构
 * <p>
 * 类加载层级：
 *
 * <pre>
 * 宿主 ClassLoader
 *     ↓ parent
 * SharedApiClassLoader (共享 API 层)
 *     ↓ parent
 * PluginClassLoader (插件实现层)
 * </pre>
 */
@Slf4j
public class DefaultPluginLoaderFactory implements PluginLoaderFactory {

    @Override
    public ClassLoader create(String pluginId, File sourceFile, ClassLoader hostClassLoader) {
        try {
            URL[] urls = resolveUrls(sourceFile);

            // 确定插件 ClassLoader 的 parent
            ClassLoader parent = determineParent(hostClassLoader);

            // ✅ 创建插件 ClassLoader
            PluginClassLoader pluginCL = new PluginClassLoader(pluginId, urls, parent);
            log.debug("[{}] 创建 PluginClassLoader, parent={}", pluginId, parent);

            return pluginCL;
        } catch (MalformedURLException e) {
            throw new ClassLoaderException(pluginId, sourceFile.getPath(), "创建 PluginClassLoader 失败", e);
        }
    }

    /**
     * 确定插件 ClassLoader 的 parent
     * 如果启用了三层结构，使用 SharedApiClassLoader 作为 parent
     */
    private ClassLoader determineParent(ClassLoader hostClassLoader) {
        // 三层结构：插件 CL -> SharedApi CL -> 宿主 CL
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