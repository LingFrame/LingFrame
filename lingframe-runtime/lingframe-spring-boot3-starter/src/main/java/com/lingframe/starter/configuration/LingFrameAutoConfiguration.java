package com.lingframe.starter.configuration;

import com.lingframe.api.context.PluginContext;
import com.lingframe.api.security.PermissionService;
import com.lingframe.core.context.CorePluginContext;
import com.lingframe.core.event.EventBus;
import com.lingframe.core.plugin.PluginManager;
import com.lingframe.core.security.DefaultPermissionService;
import com.lingframe.core.spi.ContainerFactory;
import com.lingframe.starter.adapter.SpringContainerFactory;
import com.lingframe.starter.processor.LingReferenceInjector;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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

    @Bean
    public ContainerFactory containerFactory(ApplicationContext parentContext) {
        return new SpringContainerFactory(parentContext);
    }

    // 3. PluginManager 依然是核心，但现在它依赖注入进来的组件
    @Bean
    public PluginManager pluginManager(ContainerFactory containerFactory) {
        // 注意：这里需要修改 PluginManager 的构造函数来支持注入
        return new PluginManager(containerFactory);
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
}