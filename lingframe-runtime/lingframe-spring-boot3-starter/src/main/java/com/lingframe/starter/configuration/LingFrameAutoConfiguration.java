package com.lingframe.starter.configuration;

import com.lingframe.api.context.PluginContext;
import com.lingframe.api.security.PermissionService;
import com.lingframe.core.context.CorePluginContext;
import com.lingframe.core.event.EventBus;
import com.lingframe.core.kernel.GovernanceKernel;
import com.lingframe.core.plugin.PluginManager;
import com.lingframe.core.security.DefaultPermissionService;
import com.lingframe.core.spi.ContainerFactory;
import com.lingframe.starter.adapter.SpringContainerFactory;
import com.lingframe.starter.processor.LingReferenceInjector;
import com.lingframe.starter.web.LingWebProxyController;
import com.lingframe.starter.web.WebInterfaceManager;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.lang.reflect.Method;

@Configuration
public class LingFrameAutoConfiguration {

    // 1. 将权限服务注册为 Bean (解耦)
    @Bean
    @ConditionalOnMissingBean
    public PermissionService permissionService() {
        return new DefaultPermissionService();
    }

    // 2. 将事件总线注册为 Bean (解耦)
    @Bean
    @ConditionalOnMissingBean
    public EventBus eventBus() {
        return new EventBus();
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

    // 3. PluginManager 依然是核心，但现在它依赖注入进来的组件
    @Bean
    public PluginManager pluginManager(ContainerFactory containerFactory, GovernanceKernel governanceKernel) {
        return new PluginManager(containerFactory, governanceKernel);
    }

    // 4. 【关键】额外注册一个代表宿主的 Context
    @Bean
    public PluginContext hostPluginContext(PluginManager pluginManager,
                                           PermissionService permissionService,
                                           EventBus eventBus) {
        // 给宿主应用一个固定的 ID，例如 "host-app"
        return new CorePluginContext("host-app", pluginManager, permissionService, eventBus);
    }

    // [新增] 注册 LingReference 注入器
    @Bean
    public LingReferenceInjector lingReferenceInjector(PluginManager pluginManager) {
        return new LingReferenceInjector(pluginManager);
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