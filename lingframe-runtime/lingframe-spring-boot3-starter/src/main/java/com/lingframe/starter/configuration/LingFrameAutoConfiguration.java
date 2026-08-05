package com.lingframe.starter.configuration;

import com.lingframe.core.pipeline.InvocationPipelineEngine;
import com.lingframe.core.metrics.MetricsCollector;
import com.lingframe.starter.config.LingFrameProperties;
import com.lingframe.starter.filter.LingWebGovernanceFilter;
import com.lingframe.starter.governance.EntryInvocationGovernanceResolver;
import com.lingframe.starter.web.JakartaRepeatableReadFilterFactory;
import com.lingframe.starter.web.LingGatewayHandlerMapping;
import com.lingframe.starter.web.WebInterfaceManager;
import com.lingframe.starter.web.WebRouteResolver;
import com.lingframe.starter.web.LingOpenApiCustomizerAdapter;
import org.springdoc.core.customizers.GlobalOpenApiCustomizer;
import org.springframework.beans.factory.ObjectProvider;
import lombok.extern.slf4j.Slf4j;
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
 * 面向 Spring Boot 3.x 的自动配置入口。
 * <p>
 * 显式导入 {@link JakartaRepeatableReadFilterFactory} 作为可重复读 Filter 的唯一注册点。
 */
@Slf4j
@AutoConfiguration
@ConditionalOnWebApplication
@ConditionalOnProperty(prefix = "lingframe", name = "enabled", havingValue = "true", matchIfMissing = true)
@Import({LingFrameCoreConfiguration.class, JakartaRepeatableReadFilterFactory.class})
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
        registration.setOrder(1);
        registration.setName("lingWebGovernanceFilter");
        return registration;
    }

    @Bean
    public LingGatewayHandlerMapping lingGatewayHandlerMapping(
            WebRouteResolver webRouteResolver,
            WebInterfaceManager webInterfaceManager,
            LingFrameProperties properties) {
        return new LingGatewayHandlerMapping(webRouteResolver, webInterfaceManager,
                properties.getTrustedForwardedPrefixes());
    }

    @Bean
    @ConditionalOnProperty(prefix = "springdoc.api-docs", name = "enabled", matchIfMissing = true)
    public GlobalOpenApiCustomizer lingGlobalOpenApiCustomizer(LingOpenApiCustomizerAdapter adapter) {
        return adapter::customise;
    }

    @Bean
    public ApplicationListener<ContextRefreshedEvent> lingWebInitializer(
            WebInterfaceManager manager,
            @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping coreMapping,
            RequestMappingHandlerAdapter adapter) {
        return event -> {
            if (event.getApplicationContext().getParent() == null) {
                if (event.getApplicationContext() instanceof ConfigurableApplicationContext) {
                    ConfigurableApplicationContext cac = (ConfigurableApplicationContext) event.getApplicationContext();
                    manager.init(coreMapping, adapter, cac);
                }
            }
        };
    }
}
