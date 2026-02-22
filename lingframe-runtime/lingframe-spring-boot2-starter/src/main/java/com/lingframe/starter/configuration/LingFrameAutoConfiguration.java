package com.lingframe.starter.configuration;

import com.lingframe.core.kernel.GovernanceKernel;
import com.lingframe.core.ling.LingManager;
import com.lingframe.starter.config.LingFrameProperties;
import com.lingframe.starter.filter.LingWebGovernanceFilter;
import com.lingframe.starter.web.WebInterfaceManager;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Spring Boot 2.x 自动配置入口
 * <p>
 * 通过 {@code @Import} 引入版本无关的 {@link LingFrameCoreConfiguration}，
 * 仅在此注册 javax.servlet 版本的 Filter。
 */
@Configuration
@ConditionalOnProperty(prefix = "lingframe", name = "enabled", havingValue = "true", matchIfMissing = true)
@Import(LingFrameCoreConfiguration.class)
public class LingFrameAutoConfiguration {

    @Bean
    public FilterRegistrationBean<LingWebGovernanceFilter> lingWebGovernanceFilter(
            GovernanceKernel governanceKernel,
            LingManager lingManager,
            WebInterfaceManager webInterfaceManager,
            LingFrameProperties properties,
            @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping handlerMapping) {
        FilterRegistrationBean<LingWebGovernanceFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(
                new LingWebGovernanceFilter(governanceKernel, lingManager, webInterfaceManager, properties,
                        handlerMapping));
        registration.addUrlPatterns("/*");
        registration.setOrder(1);
        registration.setName("lingWebGovernanceFilter");
        return registration;
    }
}