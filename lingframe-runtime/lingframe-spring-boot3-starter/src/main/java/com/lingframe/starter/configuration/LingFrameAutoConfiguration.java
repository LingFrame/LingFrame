package com.lingframe.starter.configuration;

import com.lingframe.api.context.PluginContext;
import com.lingframe.api.security.PermissionService;
import com.lingframe.core.classloader.DefaultPluginLoaderFactory;
import com.lingframe.core.context.CorePluginContext;
import com.lingframe.core.dev.HotSwapWatcher;
import com.lingframe.core.event.EventBus;
import com.lingframe.core.governance.GovernanceArbitrator;
import com.lingframe.core.governance.HostGovernanceRule;
import com.lingframe.core.governance.LocalGovernanceRegistry;
import com.lingframe.core.governance.provider.StandardGovernancePolicyProvider;
import com.lingframe.core.invoker.DefaultPluginServiceInvoker;
import com.lingframe.core.kernel.GovernanceKernel;
import com.lingframe.core.plugin.PluginManager;
import com.lingframe.core.router.LabelMatchRouter;
import com.lingframe.core.security.DefaultPermissionService;
import com.lingframe.core.spi.*;
import com.lingframe.starter.adapter.SpringContainerFactory;
import com.lingframe.starter.config.LingFrameProperties;
import com.lingframe.starter.processor.LingReferenceInjector;
import com.lingframe.starter.web.LingWebProxyController;
import com.lingframe.starter.web.WebInterfaceManager;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Slf4j
@Configuration
@EnableConfigurationProperties(LingFrameProperties.class)
@ConditionalOnProperty(prefix = "lingframe", name = "enabled", havingValue = "true", matchIfMissing = true)
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
    public StandardGovernancePolicyProvider standardGovernancePolicyProvider(
            LocalGovernanceRegistry registry,
            LingFrameProperties properties) {

        List<HostGovernanceRule> coreRules = new ArrayList<>();
        if (properties.getRules() != null) {
            for (LingFrameProperties.GovernanceRule r : properties.getRules()) {
                coreRules.add(HostGovernanceRule.builder()
                        .pattern(r.getPattern())
                        .permission(r.getPermission())
                        .accessType(r.getAccess())
                        .auditEnabled(r.getAudit())
                        .auditAction(r.getAuditAction())
                        .timeout(r.getTimeout())
                        .build());
            }
        }

        return new StandardGovernancePolicyProvider(registry, coreRules);
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

    @Bean
    public GovernanceKernel governanceKernel(PermissionService permissionService, GovernanceArbitrator arbitrator) {
        return new GovernanceKernel(permissionService, arbitrator);
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
                                       PluginLoaderFactory pluginLoaderFactory,
                                       ObjectProvider<List<PluginSecurityVerifier>> verifiersProvider,
                                       EventBus eventBus,
                                       TrafficRouter trafficRouter,
                                       PluginServiceInvoker pluginServiceInvoker,
                                       ObjectProvider<TransactionVerifier> transactionVerifierProvider,
                                       ObjectProvider<List<ThreadLocalPropagator>> propagatorsProvider) {

        // 获取可选 Bean
        TransactionVerifier transactionVerifier = transactionVerifierProvider.getIfAvailable();
        List<ThreadLocalPropagator> propagators = propagatorsProvider.getIfAvailable(Collections::emptyList);
        List<PluginSecurityVerifier> verifiers = verifiersProvider.getIfAvailable(Collections::emptyList);

        return new PluginManager(containerFactory, permissionService, governanceKernel,
                pluginLoaderFactory, verifiers, eventBus, trafficRouter, pluginServiceInvoker,
                transactionVerifier, propagators);
    }

    @Bean
    @ConditionalOnProperty(prefix = "lingframe", name = "dev-mode", havingValue = "true")
    public HotSwapWatcher hotSwapWatcher(PluginManager pluginManager, EventBus eventBus) {
        return new HotSwapWatcher(pluginManager, eventBus);
    }

    // 额外注册一个代表宿主的 Context
    @Bean
    public PluginContext hostPluginContext(PluginManager pluginManager,
                                           PermissionService permissionService,
                                           EventBus eventBus) {
        // 给宿主应用一个固定的 ID，例如 "host-app"
        return new CorePluginContext("host-app", pluginManager, permissionService, eventBus);
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
    public LingWebProxyController lingWebProxyController(WebInterfaceManager manager, PluginManager pluginManager,
                                                         GovernanceKernel governanceKernel) {
        return new LingWebProxyController(manager, pluginManager, governanceKernel);
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