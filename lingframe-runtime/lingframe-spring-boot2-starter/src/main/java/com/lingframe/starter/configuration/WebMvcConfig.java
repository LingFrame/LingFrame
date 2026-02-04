package com.lingframe.starter.configuration;

import com.lingframe.starter.interceptor.LingWebGovernanceInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 自定义拦截器的注册
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final LingWebGovernanceInterceptor governanceInterceptor;

    public WebMvcConfig(LingWebGovernanceInterceptor governanceInterceptor) {
        this.governanceInterceptor = governanceInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 注册拦截器
        registry.addInterceptor(governanceInterceptor)
                .addPathPatterns("/**")  // 拦截所有路径
                .excludePathPatterns(    // 排除一些路径
                        "/error",
                        "/favicon.ico",
                        "/actuator/**",
                        "/swagger-ui/**",
                        "/v3/api-docs/**"
                )
                .order(0);  // 设置执行顺序，数字越小优先级越高
    }
}
