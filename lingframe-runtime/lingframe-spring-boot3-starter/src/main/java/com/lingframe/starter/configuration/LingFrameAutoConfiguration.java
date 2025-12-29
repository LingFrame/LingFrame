package com.lingframe.starter.configuration;

import com.lingframe.api.context.PluginContext;
import com.lingframe.api.security.PermissionService;
import com.lingframe.core.classloader.DefaultPluginLoaderFactory;
import com.lingframe.core.context.CorePluginContext;
import com.lingframe.core.event.EventBus;
import com.lingframe.core.governance.GovernanceArbitrator;
import com.lingframe.core.governance.LocalGovernanceRegistry;
import com.lingframe.core.governance.provider.StandardGovernancePolicyProvider;
import com.lingframe.core.kernel.GovernanceKernel;
import com.lingframe.core.plugin.PluginManager;
import com.lingframe.core.security.DefaultPermissionService;
import com.lingframe.core.spi.ContainerFactory;
import com.lingframe.core.spi.GovernancePolicyProvider;
import com.lingframe.core.spi.PluginLoaderFactory;
import com.lingframe.core.spi.PluginSecurityVerifier;
import com.lingframe.core.invoker.DefaultPluginServiceInvoker;
import com.lingframe.core.spi.PluginServiceInvoker;
import com.lingframe.core.router.LabelMatchRouter;
import com.lingframe.core.spi.TrafficRouter;
import com.lingframe.starter.adapter.SpringContainerFactory;
import com.lingframe.starter.config.LingFrameProperties;
import com.lingframe.starter.processor.LingReferenceInjector;
import com.lingframe.starter.web.LingWebProxyController;
import com.lingframe.starter.web.WebInterfaceManager;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.lang.reflect.Method;
import java.util.List;

@Configuration
@EnableConfigurationProperties(LingFrameProperties.class)
public class LingFrameAutoConfiguration {

    // 将事件总线注册为 Bean (解耦)
    @Bean
    @ConditionalOnMissingBean
    public EventBus eventBus() {
        return new EventBus();
    }

    @Bean
    public LocalGovernanceRegistry localGovernanceRegistry(EventBus eventBus) {
        return new LocalGovernanceRegistry(eventBus);
    }

    // 默认的插件加载器工厂
    @Bean
    @ConditionalOnMissingBean(PluginLoaderFactory.class)
    public PluginLoaderFactory defaultPluginLoaderFactory() {
        return new DefaultPluginLoaderFactory();
    }

    @Bean
    public StandardGovernancePolicyProvider standardGovernancePolicyProvider(LocalGovernanceRegistry registry,
                                                                             LingFrameProperties properties) {
        // 将 Spring 配置注入到纯 Java 的内核组件中
        return new StandardGovernancePolicyProvider(registry, properties.getForcePermissions());
    }

    // 组装仲裁器：收集容器中所有的 PolicyProvider
    @Bean
    public GovernanceArbitrator governanceArbitrator(List<GovernancePolicyProvider> providers) {
        return new GovernanceArbitrator(providers);
    }

    // 将权限服务注册为 Bean (解耦)
    @Bean
    @ConditionalOnMissingBean
    public PermissionService permissionService() {
        return new DefaultPermissionService();
    }

    // 组装 GovernanceKernel
    @Bean
    public GovernanceKernel governanceKernel(PermissionService permissionService) {
        return new GovernanceKernel(permissionService);
    }

    @Bean
    public ContainerFactory containerFactory(ApplicationContext parentContext) {
        return new SpringContainerFactory(parentContext);
    }

    @Bean
    public TrafficRouter trafficRouter() {
        return new LabelMatchRouter();
    }

    @Bean
    public PluginServiceInvoker pluginServiceInvoker() {
        return new DefaultPluginServiceInvoker();
    }

    @Bean
    public PluginManager pluginManager(ContainerFactory containerFactory,
                                       PermissionService permissionService,
                                       GovernanceKernel governanceKernel,
                                       GovernanceArbitrator governanceArbitrator,
                                       PluginLoaderFactory pluginLoaderFactory,
                                       List<PluginSecurityVerifier> verifiers,
                                       EventBus eventBus,
                                       TrafficRouter trafficRouter,
                                       PluginServiceInvoker pluginServiceInvoker) {
        return new PluginManager(containerFactory, permissionService, governanceKernel,
                governanceArbitrator, pluginLoaderFactory, verifiers, eventBus, trafficRouter, pluginServiceInvoker);
    }

    // 额外注册一个代表宿主的 Context
    @Bean
    public PluginContext hostPluginContext(PluginManager pluginManager,
                                           PermissionService permissionService,
                                           GovernanceKernel governanceKernel,
                                           EventBus eventBus) {
        // 给宿主应用一个固定的 ID，例如 "host-app"
        return new CorePluginContext("host-app", pluginManager, permissionService, governanceKernel, eventBus);
    }

    // 注册 LingReference 注入器
    @Bean
    public LingReferenceInjector lingReferenceInjector(PluginManager pluginManager) {
        return new LingReferenceInjector(pluginManager, "host-app");
    }

    @Bean
    public WebInterfaceManager webInterfaceManager() {
        return new WebInterfaceManager();
    }

    @Bean
    public LingWebProxyController lingWebProxyController(WebInterfaceManager manager, GovernanceKernel governanceKernel) {
        return new LingWebProxyController(manager, governanceKernel);
    }

    @Bean
    public ApplicationListener<ContextRefreshedEvent> lingWebInitializer(
            WebInterfaceManager manager,
            LingWebProxyController controller,
            @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping hostMapping
    ) {
        return event -> {
            if (event.getApplicationContext().getParent() == null) { // 仅 Host 容器执行
                try {
                    // 获取 dispatch 方法反射对象
                    Method method = LingWebProxyController.class.getMethod(
                            "dispatch", HttpServletRequest.class, HttpServletResponse.class);

                    manager.init(hostMapping, controller, method);

                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        };
    }
}