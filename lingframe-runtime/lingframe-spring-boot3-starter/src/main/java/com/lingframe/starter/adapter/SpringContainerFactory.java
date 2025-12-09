package com.lingframe.starter.adapter;

import com.lingframe.core.spi.ContainerFactory;
import com.lingframe.core.spi.PluginContainer;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.io.DefaultResourceLoader;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.stream.Stream;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class SpringContainerFactory implements ContainerFactory {

    private final ApplicationContext parentContext;

    public SpringContainerFactory(ApplicationContext parentContext) {
        this.parentContext = parentContext;
    }

    @Override
    public PluginContainer create(String pluginId, File sourceFile, ClassLoader classLoader) {
        String mainClass;

        if (sourceFile.isDirectory()) {
            // 【开发模式】扫描目录
            mainClass = scanMainClass(sourceFile);
        } else {
            // 【生产模式】读取 Manifest
            mainClass = getMainClassFromJar(sourceFile);
        }

        if (mainClass == null) {
            log.error("Cannot find Main-Class for plugin: {}", pluginId);
            return null;
        }
        try {
            Class<?> sourceClass = classLoader.loadClass(mainClass);

            SpringApplicationBuilder builder = new SpringApplicationBuilder()
                    .parent((ConfigurableApplicationContext) parentContext) // 父子上下文
                    .resourceLoader(new DefaultResourceLoader(classLoader)) // 使用隔离加载器
                    .sources(sourceClass)
                    .bannerMode(Banner.Mode.OFF)
                    .web(WebApplicationType.NONE) // 【生产级】禁止插件启动 Tomcat
                    .properties("spring.main.allow-bean-definition-overriding=true") // 【生产级】允许覆盖 Bean
                    .properties("spring.application.name=plugin-" + pluginId); // 【生产级】独立应用名

            return new SpringPluginContainer(builder, classLoader);
        } catch (Exception e) {
            log.error("Create container failed for plugin: {}", pluginId, e);
            return null;
        }
    }

    private String getMainClassFromJar(File jarFile) {
        try (JarFile jar = new JarFile(jarFile)) {
            Manifest manifest = jar.getManifest();
            Attributes attrs = manifest.getMainAttributes();
            String cls = attrs.getValue("Start-Class");
            if (cls == null) cls = attrs.getValue("Main-Class");
            return cls;
        } catch (Exception e) {
            log.error("Error reading manifest from jar: {}", jarFile.getAbsolutePath(), e);
            return null;
        }
    }

    /**
     * 简单扫描目录下的主类
     * 规则：寻找标注了 @SpringBootApplication 的类
     */
    private String scanMainClass(File dir) {
        try (Stream<Path> stream = Files.walk(dir.toPath())) {
            return stream
                    .filter(p -> p.toString().endsWith(".class"))
                    .map(p -> {
                        // 简单的将路径转换为类名 (注意：这需要基于 dir 是 classpath root 的假设)
                        // 例如: /target/classes/com/example/App.class -> com.example.App
                        String rel = dir.toPath().relativize(p).toString();
                        return rel.replace(File.separator, ".").replace(".class", "");
                    })
                    .filter(className -> {
                        // 这里不能用 loadClass，因为类加载器还没准备好，而且全量加载太慢
                        // 简化做法：只匹配类名特征，或假设主类名为 PluginApplication
                        // 生产级做法应使用 ASM 读取字节码注解
                        return className.endsWith("Application") || className.endsWith("Plugin");
                    })
                    .findFirst()
                    .orElse(null);
        } catch (IOException e) {
            log.error("Failed to scan main class in directory: {}", dir.getAbsolutePath(), e);
            return null;
        }
    }
}