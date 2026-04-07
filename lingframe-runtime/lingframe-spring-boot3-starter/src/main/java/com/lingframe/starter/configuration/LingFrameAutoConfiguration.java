package com.lingframe.starter.configuration;

import com.lingframe.core.pipeline.InvocationPipelineEngine;
import com.lingframe.core.metrics.MetricsCollector;
import com.lingframe.starter.config.LingFrameProperties;
import com.lingframe.starter.filter.LingWebGovernanceFilter;
import com.lingframe.starter.governance.EntryInvocationGovernanceResolver;
import com.lingframe.starter.web.WebInterfaceManager;
import com.lingframe.starter.web.WebRouteResolver;
import com.lingframe.starter.web.LingOpenApiCustomizerAdapter;
import org.springdoc.core.customizers.GlobalOpenApiCustomizer;
import org.springdoc.core.models.GroupedOpenApi;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.util.ReflectionUtils;
import lombok.extern.slf4j.Slf4j;
import java.lang.reflect.Field;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * 面向 Spring Boot 3.x 的自动配置入口
 * <p>
 * 通过 {@code @Import} 引入版本无关的 {@link LingFrameCoreConfiguration}，
 * 仅在此注册 jakarta.servlet 版本的 Filter。
 */
@Slf4j
@AutoConfiguration
@ConditionalOnWebApplication
@ConditionalOnProperty(prefix = "lingframe", name = "enabled", havingValue = "true", matchIfMissing = true)
@Import(LingFrameCoreConfiguration.class)
public class LingFrameAutoConfiguration {

    @Bean
    public FilterRegistrationBean<LingWebGovernanceFilter> lingWebGovernanceFilter(
            WebRouteResolver webRouteResolver,
            InvocationPipelineEngine pipelineEngine,
            LingFrameProperties properties,
            EntryInvocationGovernanceResolver invocationGovernanceResolver,
            ObjectProvider<MetricsCollector> metricsCollectorProvider,
            @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping handlerMapping) {
        FilterRegistrationBean<LingWebGovernanceFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(
                new LingWebGovernanceFilter(webRouteResolver, pipelineEngine, properties,
                        handlerMapping, metricsCollectorProvider, invocationGovernanceResolver));
        registration.addUrlPatterns("/*");
        registration.setOrder(1); // 高优先级
        registration.setName("lingWebGovernanceFilter");
        return registration;
    }

    @Bean
    @ConditionalOnProperty(prefix = "springdoc.api-docs", name = "enabled", matchIfMissing = true)
    public GlobalOpenApiCustomizer lingGlobalOpenApiCustomizer(LingOpenApiCustomizerAdapter adapter) {
        return adapter::customise;
    }

    @Bean
    public static BeanPostProcessor lingGroupedOpenApiPostProcessor(LingOpenApiCustomizerAdapter adapter) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof GroupedOpenApi) {
                    GroupedOpenApi groupedOpenApi = (GroupedOpenApi) bean;
                    
                    // SpringDoc 2.x 鐨?GroupedOpenApi 瀛楁鍚嶅彲鑳芥槸 postProcessors 鎴?openApiCustomisers
                    try {
                        Field field = ReflectionUtils.findField(GroupedOpenApi.class, "openApiCustomisers");
                        if (field == null) {
                            field = ReflectionUtils.findField(GroupedOpenApi.class, "postProcessors");
                        }
                        
                        if (field != null) {
                            ReflectionUtils.makeAccessible(field);
                            @SuppressWarnings("unchecked")
                            List<OpenApiCustomizer> customizers = (List<OpenApiCustomizer>) field.get(groupedOpenApi);
                            if (customizers != null) {
                                customizers.add(openApi -> adapter.customise(
                                    openApi, 
                                    groupedOpenApi.getPathsToMatch(),
                                    groupedOpenApi.getPackagesToScan(),
                                    groupedOpenApi.getPathsToExclude(),
                                    groupedOpenApi.getPackagesToExclude()
                                ));
                                log.debug("[LingFrame Web] Attached full path-and-package aware customizer to GroupedOpenApi: {}", beanName);
                            }
                        }
                    } catch (Exception e) {
                        log.warn("[LingFrame Web] Failed to attach customizer to GroupedOpenApi [{}]: {}", beanName, e.getMessage());
                    }
                }
                return bean;
            }
        };
    }

    @Bean
    public ApplicationListener<ContextRefreshedEvent> lingWebInitializer(
            WebInterfaceManager manager,
            @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping hostMapping,
            RequestMappingHandlerAdapter adapter) {
        return event -> {
            if (event.getApplicationContext().getParent() == null) {
                if (event.getApplicationContext() instanceof ConfigurableApplicationContext) {
                    ConfigurableApplicationContext cac = (ConfigurableApplicationContext) event.getApplicationContext();
                    manager.init(hostMapping, adapter, cac);
                }
            }
        };
    }
}
