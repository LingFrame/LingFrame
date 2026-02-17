package com.lingframe.starter.adapter;

import com.lingframe.core.exception.PluginInstallException;
import com.lingframe.core.spi.ContainerFactory;
import com.lingframe.core.spi.PluginContainer;
import com.lingframe.starter.config.LingFrameProperties;
import com.lingframe.starter.loader.AsmMainClassScanner;
import com.lingframe.starter.web.WebInterfaceManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;

import java.io.File;
import java.util.List;

@Slf4j
public class SpringContainerFactory implements ContainerFactory {

    private final boolean devMode;
    private final ApplicationContext parentContext;
    private final WebInterfaceManager webInterfaceManager;
    private final List<String> serviceExcludedPackages;

    public SpringContainerFactory(ApplicationContext parentContext, WebInterfaceManager webInterfaceManager) {
        LingFrameProperties props = parentContext.getBean(LingFrameProperties.class);
        this.devMode = props.isDevMode();
        this.parentContext = parentContext;
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
                    // 🔥 不设置父容器，实现完全隔离
                    // 原因：
                    // 1. 父子容器关系导致宿主 BeanFactory 持有子容器引用，造成 ClassLoader 泄漏
                    // 2. 零信任设计：插件不应直接访问宿主 Bean，应通过 PluginContext
                    // 3. 核心 Bean (PluginManager, PluginContext) 已在 registerBeans() 中手动注入
                    .resourceLoader(new DefaultResourceLoader(classLoader)) // 使用隔离加载器
                    .sources(sourceClass)
                    .bannerMode(Banner.Mode.OFF)
                    .web(WebApplicationType.NONE) // 禁止插件启动 Tomcat
                    .properties("spring.main.allow-bean-definition-overriding=true") // 允许覆盖 Bean
                    .properties("spring.application.name=plugin-" + pluginId) // 独立应用名
                    .properties("spring.sql.init.mode=never") // 禁用 Spring Boot 自动 SQL 初始化
                    // 显式排除 JMX 相关自动配置，防止 MBean 名称冲突
                    .properties("spring.autoconfigure.exclude=" +
                            "org.springframework.boot.autoconfigure.admin.SpringApplicationAdminJmxAutoConfiguration," +
                            "org.springframework.boot.autoconfigure.jmx.JmxAutoConfiguration," +
                            "org.springframework.boot.actuate.autoconfigure.endpoint.jmx.JmxEndpointAutoConfiguration");

            // 🔥 获取宿主的 Adapter（用于清理缓存）
            RequestMappingHandlerAdapter hostAdapter = null;
            try {
                hostAdapter = parentContext.getBean(RequestMappingHandlerAdapter.class);
            } catch (Exception e) {
                log.debug("No RequestMappingHandlerAdapter found in host context");
            }

            return new SpringPluginContainer(
                    builder,
                    classLoader,
                    webInterfaceManager,
                    serviceExcludedPackages,
                    (ConfigurableApplicationContext) parentContext, // 🔥 传入宿主 Context
                    hostAdapter // 🔥 传入宿主 Adapter
            );

        } catch (Exception e) {
            log.error("[{}] Create container failed", pluginId, e);
            if (devMode) {
                throw new PluginInstallException(pluginId, "Failed to create Spring container", e);
            }
            return null;
        }
    }
}
