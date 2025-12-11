package com.lingframe.plugin.cache.configuration;

import com.lingframe.api.security.PermissionService;
import com.lingframe.plugin.cache.proxy.LingCacheProxy;
import com.lingframe.plugin.cache.proxy.LingRedisTemplateProxy;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import com.github.benmanes.caffeine.cache.Cache;

@Component
public class CacheWrapperProcessor implements BeanPostProcessor, ApplicationContextAware {

    private ApplicationContext applicationContext;

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        // 如果 Bean 是 RedisTemplate，就把它包一层
        if (bean instanceof RedisTemplate) {
            System.out.println(">>>>>> [LingFrame] Wrapping RedisTemplate: " + beanName);

            // 延迟获取 PermissionService，确保 ApplicationContext 已经准备好
            if (applicationContext != null) {
                try {
                    PermissionService permissionService = applicationContext.getBean(PermissionService.class);
                    return new LingRedisTemplateProxy<>((RedisTemplate) bean, permissionService);
                } catch (Exception e) {
                    System.err.println("Failed to wrap RedisTemplate: " + e.getMessage());
                }
            }
        }

        // 如果 Bean 是 Caffeine Cache，就把它包一层
        if (bean instanceof Cache) {
            System.out.println(">>>>>> [LingFrame] Wrapping Cache: " + beanName);

            // 延迟获取 PermissionService，确保 ApplicationContext 已经准备好
            if (applicationContext != null) {
                try {
                    PermissionService permissionService = applicationContext.getBean(PermissionService.class);
                    return new LingCacheProxy<>((Cache) bean, permissionService);
                } catch (Exception e) {
                    System.err.println("Failed to wrap Cache: " + e.getMessage());
                }
            }
        }

        return bean;
    }

    @Override
    public Object postProcessBeforeInitialization(Object bean, String beanName) throws BeansException {
        return bean;
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }
}