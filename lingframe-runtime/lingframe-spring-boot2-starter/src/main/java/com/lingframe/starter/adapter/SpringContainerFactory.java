package com.lingframe.starter.adapter;

import com.lingframe.core.exception.PluginInstallException;
import com.lingframe.core.spi.ContainerFactory;
import com.lingframe.core.spi.PluginContainer;
import com.lingframe.starter.config.LingFrameProperties;
import com.lingframe.starter.util.AsmMainClassScanner;
import com.lingframe.starter.web.WebInterfaceManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.io.DefaultResourceLoader;

import java.io.File;
import java.util.List;

@Slf4j
public class SpringContainerFactory implements ContainerFactory {

    private final ApplicationContext parentContext;
    private final boolean devMode;
    private final WebInterfaceManager webInterfaceManager;
    private final List<String> serviceExcludedPackages;

    public SpringContainerFactory(ApplicationContext parentContext, WebInterfaceManager webInterfaceManager) {
        this.parentContext = parentContext;
        LingFrameProperties props = parentContext.getBean(LingFrameProperties.class);
        this.devMode = props.isDevMode();
        this.serviceExcludedPackages = props.getServiceExcludedPackages();
        this.webInterfaceManager = webInterfaceManager;
    }

    @Override
    public PluginContainer create(String pluginId, File sourceFile, ClassLoader classLoader) {
        try {
            String mainClass = AsmMainClassScanner.discoverMainClass(pluginId, sourceFile, classLoader);
            log.info("[{}] Found Main-Class: {}", pluginId, mainClass);

            Class<?> sourceClass = classLoader.loadClass(mainClass);

            SpringApplicationBuilder builder = new SpringApplicationBuilder()
                    .parent((ConfigurableApplicationContext) parentContext) // 父子上下文
                    .resourceLoader(new DefaultResourceLoader(classLoader)) // 使用隔离加载器
                    .sources(sourceClass)
                    .bannerMode(Banner.Mode.OFF)
                    .web(WebApplicationType.NONE) // 禁止插件启动 Tomcat
                    .properties("spring.main.allow-bean-definition-overriding=true") // 允许覆盖 Bean
                    .properties("spring.application.name=plugin-" + pluginId) // 独立应用名
                    .properties("spring.sql.init.mode=never"); // 禁用 Spring Boot 自动 SQL 初始化

            return new SpringPluginContainer(builder, classLoader, webInterfaceManager, serviceExcludedPackages);

        } catch (Exception e) {
            log.error("[{}] Create container failed", pluginId, e);
            if (devMode) {
                throw new PluginInstallException(pluginId, "Failed to create Spring container", e);
            }
            return null;
        }
    }
}
