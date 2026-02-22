package com.lingframe.infra.cache.configuration;

import com.github.benmanes.caffeine.cache.Cache;
import com.lingframe.api.security.PermissionService;
import com.lingframe.infra.cache.proxy.LingCaffeineCacheProxy;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

@Slf4j
// ✅ 技术栈探测：灵核有 caffeine
@ConditionalOnClass(Cache.class)
// ✅ 核心强制：框架开启即生效
@ConditionalOnProperty(prefix = "lingframe", name = "enabled", havingValue = "true", matchIfMissing = true)
public class CaffeineWrapperProcessor implements BeanPostProcessor, ApplicationContextAware {

    private ApplicationContext applicationContext;

    @Override
    public Object postProcessAfterInitialization(@NonNull Object bean, @NonNull String beanName) throws BeansException {
        // 如果 Bean 是 Caffeine Cache，就把它包一层
        if (bean instanceof Cache) {
            log.info(">>>>>> [LingFrame] Wrapping Caffeine Cache: {}", beanName);

            // 延迟获取 PermissionService，确保 ApplicationContext 已经准备好
            if (applicationContext != null) {
                try {
                    PermissionService permissionService = applicationContext.getBean(PermissionService.class);
                    return new LingCaffeineCacheProxy<>((Cache) bean, permissionService);
                } catch (Exception e) {
                    log.error("Failed to wrap Caffeine Cache: {}", e.getMessage(), e);
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