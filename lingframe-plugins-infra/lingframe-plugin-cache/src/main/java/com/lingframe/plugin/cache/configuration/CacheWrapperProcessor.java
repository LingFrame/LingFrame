package com.lingframe.plugin.cache.configuration;

import com.lingframe.api.security.PermissionService;
import com.lingframe.plugin.cache.interceptor.RedisPermissionInterceptor;
import com.lingframe.plugin.cache.proxy.LingCacheProxy;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import com.github.benmanes.caffeine.cache.Cache;

@Slf4j
@Component
public class CacheWrapperProcessor implements BeanPostProcessor, ApplicationContextAware {

    private ApplicationContext applicationContext;

    @Override
    public Object postProcessAfterInitialization(@NonNull Object bean, @NonNull String beanName) throws BeansException {
        // 如果 Bean 是 RedisTemplate，就把它包一层
        if (bean instanceof RedisTemplate) {
            log.info(">>>>>> [LingFrame] Wrapping RedisTemplate: {}", beanName);

            // 延迟获取 PermissionService，确保 ApplicationContext 已经准备好
            if (applicationContext != null) {
                try {
                    PermissionService permissionService = applicationContext.getBean(PermissionService.class);
                    // 使用 ProxyFactory 创建动态代理
                    ProxyFactory proxyFactory = new ProxyFactory(bean);
                    proxyFactory.setProxyTargetClass(true); // 强制使用 CGLIB (保持 RedisTemplate 类型)
                    proxyFactory.addAdvice(new RedisPermissionInterceptor(permissionService));

                    return proxyFactory.getProxy();
                } catch (Exception e) {
                    log.error("Failed to wrap RedisTemplate: {}", e.getMessage(), e);
                }
            }
        }

        // 如果 Bean 是 Caffeine Cache，就把它包一层
        if (bean instanceof Cache) {
            log.info(">>>>>> [LingFrame] Wrapping Cache: {}", beanName);

            // 延迟获取 PermissionService，确保 ApplicationContext 已经准备好
            if (applicationContext != null) {
                try {
                    PermissionService permissionService = applicationContext.getBean(PermissionService.class);
                    return new LingCacheProxy<>((Cache) bean, permissionService);
                } catch (Exception e) {
                    log.error("Failed to wrap Cache: {}", e.getMessage(), e);
                }
            }
        }

        return bean;
    }

    @Override
    public Object postProcessBeforeInitialization(@NonNull Object bean, @NonNull String beanName) throws BeansException {
        return bean;
    }

    @Override
    public void setApplicationContext(@NonNull ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }
}