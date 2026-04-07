package com.lingframe.starter.configuration;

import com.lingframe.starter.web.LingOpenApiCustomizer;
import com.lingframe.starter.web.LingRepeatableReadFilter;
import com.lingframe.starter.web.LingSpringDocCustomizerBridge;
import com.lingframe.starter.web.WebInterfaceManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.lang.reflect.Method;

/**
 * Web 相关装配切片。
 */
@Slf4j
@Configuration
public class LingFrameWebSupportConfiguration {

    @Bean
    public FilterRegistrationBean<?> lingRepeatableReadFilter() {
        Object filter = LingRepeatableReadFilter.createProxy();
        if (filter == null) {
            return null;
        }

        FilterRegistrationBean<?> registration = new FilterRegistrationBean<>();
        try {
            Class<?> filterInterface = filter.getClass().getInterfaces()[0];
            Method setFilter = registration.getClass().getMethod("setFilter", filterInterface);
            setFilter.invoke(registration, filter);
        } catch (Exception e) {
            log.error("Failed to register LingRepeatableReadFilter: {}", e.getMessage());
        }

        registration.addUrlPatterns("/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.setName("lingRepeatableReadFilter");
        return registration;
    }

    @Bean
    public ApplicationListener<ApplicationStartedEvent> webInterfaceManagerInitializer(
            WebInterfaceManager webInterfaceManager,
            ObjectProvider<RequestMappingHandlerMapping> mappingProvider,
            ObjectProvider<RequestMappingHandlerAdapter> adapterProvider) {
        return event -> {
            ApplicationContext context = event.getApplicationContext();
            if (!(context instanceof ConfigurableApplicationContext)) {
                return;
            }

            RequestMappingHandlerMapping mapping = null;
            try {
                mapping = context.getBean("requestMappingHandlerMapping", RequestMappingHandlerMapping.class);
            } catch (Exception e) {
                mapping = mappingProvider.getIfUnique();
            }

            RequestMappingHandlerAdapter adapter = null;
            try {
                adapter = context.getBean("requestMappingHandlerAdapter", RequestMappingHandlerAdapter.class);
            } catch (Exception e) {
                adapter = adapterProvider.getIfUnique();
            }

            if (mapping != null && adapter != null) {
                webInterfaceManager.init(mapping, adapter, (ConfigurableApplicationContext) context);
                log.info("[LingFrame Web] WebInterfaceManager initialized with host Spring MVC components");
            } else {
                log.warn("[LingFrame Web] Standard Spring MVC components not found, skipping WebInterfaceManager initialization");
            }
        };
    }

    @Configuration
    @ConditionalOnClass(io.swagger.v3.oas.models.OpenAPI.class)
    static class SpringDocIntegrationConfiguration {

        @Bean
        public LingOpenApiCustomizer lingOpenApiCustomizer(WebInterfaceManager webInterfaceManager,
                Environment environment) {
            return new LingOpenApiCustomizer(webInterfaceManager, environment);
        }

        @Bean
        public Object lingSpringDocGlobalCustomizer(LingOpenApiCustomizer lingOpenApiCustomizer) {
            return LingSpringDocCustomizerBridge.createGlobalCustomizer(
                    LingFrameWebSupportConfiguration.class.getClassLoader(), lingOpenApiCustomizer);
        }

        @Bean
        public static BeanPostProcessor lingSpringDocGroupedOpenApiPostProcessor(
                LingOpenApiCustomizer lingOpenApiCustomizer) {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    LingSpringDocCustomizerBridge.attachToGroupedOpenApi(
                            bean.getClass().getClassLoader(), lingOpenApiCustomizer, bean);
                    return bean;
                }
            };
        }
    }
}
