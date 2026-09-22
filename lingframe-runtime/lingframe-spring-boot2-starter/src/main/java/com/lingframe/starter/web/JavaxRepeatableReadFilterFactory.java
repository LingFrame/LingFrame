package com.lingframe.starter.web;

import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.servlet.Filter;
import javax.servlet.http.HttpServletRequest;

/**
 * Spring Boot 2.x 可重复读过滤器工厂实现。
 * <p>
 * 由 SB2 starter 装配，提供 {@code javax.servlet} 版本的 Filter 实例。
 * 本类是可重复读 Filter 的<strong>唯一注册点</strong>（公共 starter 不再注册），
 * 须通过 {@link com.lingframe.starter.configuration.LingFrameAutoConfiguration}
 * 显式 {@code @Import} 装载。
 */
@ConditionalOnClass(HttpServletRequest.class)
@Configuration
public class JavaxRepeatableReadFilterFactory implements LingRepeatableReadFilterFactory {

    @Override
    @SuppressWarnings("unchecked")
    public FilterRegistrationBean<Filter> createFilterRegistration() {
        FilterRegistrationBean<Filter> registration =
                (FilterRegistrationBean<Filter>) newRegistration();
        registration.setFilter(new JavaxRepeatableReadFilter());
        return registration;
    }

    @Override
    public String servletApiPackage() {
        return "javax.servlet";
    }

    /**
     * 可重复读 Filter 的唯一 Bean 注册入口（bean 名与 SPI 常量一致）。
     */
    @Bean(name = LingRepeatableReadFilterFactory.FILTER_NAME)
    public FilterRegistrationBean<Filter> lingRepeatableReadFilter() {
        return createFilterRegistration();
    }
}
