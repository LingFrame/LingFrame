package com.lingframe.starter.adapter;

import com.lingframe.api.config.LingDefinition;
import com.lingframe.core.exception.LingInstallException;
import com.lingframe.core.spi.ContainerFactory;
import com.lingframe.core.spi.LingContainer;
import com.lingframe.core.spi.LingUnloadHook;
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
import java.util.ArrayList;
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
    private final List<LingUnloadHook> unloadHooks; // 🔥 卸载钩子列表

    public SpringContainerFactory(ApplicationContext parentContext, WebInterfaceManager webInterfaceManager,
            List<LingContextCustomizer> customizers, List<LingUnloadHook> unloadHooks) {
        LingFrameProperties props = parentContext.getBean(LingFrameProperties.class);
        this.devMode = props.isDevMode();
        this.serviceExcludedPackages = props.getServiceExcludedPackages();
        this.webInterfaceManager = webInterfaceManager;
        this.customizers = customizers != null ? customizers : Collections.emptyList();
        this.mainContext = parentContext; // 🔥 保存主容器
        this.unloadHooks = unloadHooks != null ? unloadHooks : Collections.emptyList(); // 🔥 保存卸载钩子
    }

    @Override
    public LingContainer create(LingDefinition definition, File sourceFile, ClassLoader classLoader) {
        String lingId = definition != null ? definition.getId() : null;
        String version = definition != null ? definition.getVersion() : null;
        try {
            String mainClass = AsmMainClassScanner.discoverMainClass(lingId, sourceFile, classLoader);
            log.info("[{}] Found Main-Class: {}", lingId, mainClass);

            Class<?> sourceClass = classLoader.loadClass(mainClass);

            // 校验 Servlet API 版本一致性，避免 boot2/boot3 Filter 接口错位。
            // 灵核用 WebApplicationType.NONE 禁止灵元起 Tomcat，灵元 Filter 通过灵核
            // Servlet 容器注册；若灵元自带 jakarta.servlet（boot3）而灵核是 javax.servlet
            // （boot2），Filter 接口签名不匹配，注册必然失败。
            // 该错误必须前置暴露，否则灵元部署后 Filter/Controller 静默不生效，难以诊断。
            String coreServletApi = detectServletApiPackage(SpringContainerFactory.class.getClassLoader());
            String lingServletApi = detectServletApiPackage(classLoader);
            if (coreServletApi != null && lingServletApi != null
                    && !coreServletApi.equals(lingServletApi)) {
                throw new LingInstallException(lingId,
                        "Servlet API 版本错位：灵核使用 " + coreServletApi
                                + ".servlet.Filter，灵元使用 " + lingServletApi
                                + ".servlet.Filter。请确保灵元与灵核使用相同的 Spring Boot 主版本"
                                + "（2.x → javax.servlet，3.x → jakarta.servlet）。");
            }

            List<String> excludes = new ArrayList<>();
            // 显式排除 JMX 相关自动配置，防止 MBean 名称冲突
            excludes.add("org.springframework.boot.autoconfigure.admin.SpringApplicationAdminJmxAutoConfiguration");
            excludes.add("org.springframework.boot.autoconfigure.jmx.JmxAutoConfiguration");
            excludes.add("org.springframework.boot.actuate.autoconfigure.endpoint.jmx.JmxEndpointAutoConfiguration");

            // 合并灵元 ling.yml 声明的自定义排除自动配置类
            if (definition != null && definition.getExcludeAutoConfigurations() != null) {
                for (String autoConfig : definition.getExcludeAutoConfigurations()) {
                    if (autoConfig != null && !autoConfig.trim().isEmpty() && !excludes.contains(autoConfig)) {
                        excludes.add(autoConfig.trim());
                    }
                }
            }
            String excludeStr = String.join(",", excludes);

            SpringApplicationBuilder builder = new SpringApplicationBuilder()
                    // 🔥 不设置父容器，实现完全隔离
                    // 原因：
                    // 1. 父子容器关系导致灵核 BeanFactory 持有子容器引用，造成 ClassLoader 泄漏
                    // 2. 零信任设计：灵元不应直接访问灵核 Bean，应通过 LingContext
                    // 3. 核心 Bean 已在 registerBeans() 中手动注入
                    .resourceLoader(new DefaultResourceLoader(classLoader)) // 使用隔离加载器
                    .sources(sourceClass)
                    .bannerMode(Banner.Mode.OFF)
                    .registerShutdownHook(false) // 🔥 防止在 JDK 17 下因无法反射清理 ShutdownHook 而导致 ClassLoader 永久泄漏
                    .web(WebApplicationType.NONE) // 禁止灵元启动 Tomcat
                    .properties("spring.main.allow-bean-definition-overriding=true") // 允许覆盖 Bean
                    .properties("spring.application.name=Ling-" + lingId) // 独立应用名
                    .properties("spring.sql.init.mode=never") // 禁用 Spring Boot 自动 SQL 初始化
                    // 动态设置排除的自动配置类列表
                    .properties("spring.autoconfigure.exclude=" + excludeStr);

            SpringLingContainer container = new SpringLingContainer(
                    builder,
                    classLoader,
                    webInterfaceManager,
                    serviceExcludedPackages,
                    customizers, // 🔥 传入定制器
                    mainContext,
                    unloadHooks,
                    version,
                    sourceFile);
            // 注入灵核只读配置门面，替代静态穿透 LingFrameConfig.current()
            try {
                container.setLingFrameInfo(mainContext.getBean(com.lingframe.core.config.LingFrameInfo.class));
            } catch (Exception e) {
                // 灵核未装 LingFrameInfo bean 时兜底默认值，保持向后兼容
                log.debug("[{}] LingFrameInfo bean not available, fallback to default implicit registration", lingId);
            }
            return container;

        } catch (Exception e) {
            log.error("[{}] Create container failed", lingId, e);
            if (devMode) {
                throw new LingInstallException(lingId, "Failed to create Spring container", e);
            }
            return null;
        }
    }

    /**
     * 检测 ClassLoader 能看到的 Servlet API 包名。
     * <p>
     * LingClassLoader 是 child-first：灵元自带 servlet API 时优先返回灵元版本；
     * 灵元不带时 fallback 到父加载器（灵核），返回灵核版本。
     * 两种情况都能正确反映"灵元运行时实际使用的 servlet API"。
     *
     * @return "javax"（Spring Boot 2.x）/ "jakarta"（Spring Boot 3.x）/ null（无 servlet API）
     */
    private static String detectServletApiPackage(ClassLoader cl) {
        if (cl == null) return null;
        try {
            cl.loadClass("javax.servlet.Filter");
            return "javax";
        } catch (ClassNotFoundException ignored) {
            // 灵核/灵元不带 javax.servlet，继续检测 jakarta
        }
        try {
            cl.loadClass("jakarta.servlet.Filter");
            return "jakarta";
        } catch (ClassNotFoundException ignored) {
            // 也不带 jakarta.servlet
        }
        return null;
    }
}
