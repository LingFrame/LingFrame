package com.lingframe.starter.adapter;

import com.lingframe.core.spi.ContainerFactory;
import com.lingframe.core.spi.PluginContainer;
import com.lingframe.starter.util.AsmMainClassScanner;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.io.DefaultResourceLoader;

import java.io.File;

@Slf4j
public class SpringContainerFactory implements ContainerFactory {

    private final ApplicationContext parentContext;

    public SpringContainerFactory(ApplicationContext parentContext) {
        this.parentContext = parentContext;
    }

    @Override
    public PluginContainer create(String pluginId, File sourceFile, ClassLoader classLoader) {
        try {
            String mainClass = AsmMainClassScanner.discoverMainClass(pluginId, sourceFile, classLoader);
            log.info("Found Main-Class: {}", mainClass);
            Class<?> sourceClass = classLoader.loadClass(mainClass);

            SpringApplicationBuilder builder = new SpringApplicationBuilder()
                    .parent((ConfigurableApplicationContext) parentContext) // 父子上下文
                    .resourceLoader(new DefaultResourceLoader(classLoader)) // 使用隔离加载器
                    .sources(sourceClass)
                    .bannerMode(Banner.Mode.OFF)
                    .web(WebApplicationType.NONE) // 禁止插件启动 Tomcat
                    .properties("spring.main.allow-bean-definition-overriding=true") // 允许覆盖 Bean
                    .properties("spring.application.name=plugin-" + pluginId); // 独立应用名

            return new SpringPluginContainer(builder, classLoader);
        } catch (Exception e) {
            log.error("Create container failed for plugin: {}", pluginId, e);
            return null;
        }
    }

}