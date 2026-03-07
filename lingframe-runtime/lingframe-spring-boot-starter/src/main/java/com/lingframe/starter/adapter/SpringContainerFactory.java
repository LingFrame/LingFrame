package com.lingframe.starter.adapter;

import com.lingframe.core.exception.LingInstallException;
import com.lingframe.core.spi.ContainerFactory;
import com.lingframe.core.spi.LingContainer;
import com.lingframe.core.spi.ResourceGuard;
import com.lingframe.starter.config.LingFrameProperties;
import com.lingframe.starter.loader.AsmMainClassScanner;
import com.lingframe.starter.web.WebInterfaceManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ApplicationContext;
import org.springframework.core.io.DefaultResourceLoader;

import java.io.File;
import java.util.List;
import java.util.Collections;

import com.lingframe.starter.spi.LingContextCustomizer;

@Slf4j
public class SpringContainerFactory implements ContainerFactory {

    private final boolean devMode;
    private final WebInterfaceManager webInterfaceManager;
    private final List<String> serviceExcludedPackages;
    private final List<LingContextCustomizer> customizers; // 新增定制器列表
    private final ApplicationContext mainContext; // 🔥 主容器引用
    private final List<ResourceGuard> resourceGuards; // 🔥 资源守卫列表

    public SpringContainerFactory(ApplicationContext parentContext, WebInterfaceManager webInterfaceManager,
            List<LingContextCustomizer> customizers, List<ResourceGuard> resourceGuards) {
        LingFrameProperties props = parentContext.getBean(LingFrameProperties.class);
        this.devMode = props.isDevMode();
        this.serviceExcludedPackages = props.getServiceExcludedPackages();
        this.webInterfaceManager = webInterfaceManager;
        this.customizers = customizers != null ? customizers : Collections.emptyList();
        this.mainContext = parentContext; // 🔥 保存主容器
        this.resourceGuards = resourceGuards != null ? resourceGuards : Collections.emptyList(); // 🔥 保存资源守卫
    }

    @Override
    public LingContainer create(String lingId, File sourceFile, ClassLoader classLoader) {
        try {
            String mainClass = AsmMainClassScanner.discoverMainClass(lingId, sourceFile, classLoader);
            log.info("[{}] Found Main-Class: {}", lingId, mainClass);

            Class<?> sourceClass = classLoader.loadClass(mainClass);

            SpringApplicationBuilder builder = new SpringApplicationBuilder()
                    // 🔥 不设置父容器，实现完全隔离
                    // 原因：
                    // 1. 父子容器关系导致灵核 BeanFactory 持有子容器引用，造成 ClassLoader 泄漏
                    // 2. 零信任设计：灵元不应直接访问灵核 Bean，应通过 LingContext
                    // 3. 核心 Bean (LingManager, LingContext) 已在 registerBeans() 中手动注入
                    .resourceLoader(new DefaultResourceLoader(classLoader)) // 使用隔离加载器
                    .sources(sourceClass)
                    .bannerMode(Banner.Mode.OFF)
                    .web(WebApplicationType.NONE) // 禁止灵元启动 Tomcat
                    .properties("spring.main.allow-bean-definition-overriding=true") // 允许覆盖 Bean
                    .properties("spring.application.name=Ling-" + lingId) // 独立应用名
                    .properties("spring.sql.init.mode=never") // 禁用 Spring Boot 自动 SQL 初始化
                    // 显式排除 JMX 相关自动配置，防止 MBean 名称冲突
                    .properties("spring.autoconfigure.exclude=" +
                            "org.springframework.boot.autoconfigure.admin.SpringApplicationAdminJmxAutoConfiguration," +
                            "org.springframework.boot.autoconfigure.jmx.JmxAutoConfiguration," +
                            "org.springframework.boot.actuate.autoconfigure.endpoint.jmx.JmxEndpointAutoConfiguration");

            return new SpringLingContainer(
                    builder,
                    classLoader,
                    webInterfaceManager,
                    serviceExcludedPackages,
                    customizers, // 🔥 传入定制器
                    mainContext,
                    resourceGuards // 🔥 传入资源守卫列表
            );

        } catch (Exception e) {
            log.error("[{}] Create container failed", lingId, e);
            if (devMode) {
                throw new LingInstallException(lingId, "Failed to create Spring container", e);
            }
            return null;
        }
    }
}
