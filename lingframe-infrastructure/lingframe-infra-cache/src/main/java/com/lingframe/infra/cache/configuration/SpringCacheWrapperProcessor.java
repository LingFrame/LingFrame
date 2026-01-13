package com.lingframe.infra.cache.configuration;

import com.lingframe.api.security.PermissionService;
import com.lingframe.infra.cache.proxy.LingCacheManagerProxy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.CacheManager;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

@Slf4j
@RequiredArgsConstructor
// ✅ 只要有 CacheManager 类就加载，不管底层实现是啥
@ConditionalOnClass(CacheManager.class)
@ConditionalOnProperty(prefix = "lingframe", name = "enabled", havingValue = "true", matchIfMissing = true)
// ✅ 确保 LingFrame 的包装器最先执行
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SpringCacheWrapperProcessor implements BeanPostProcessor, ApplicationContextAware {

    private ApplicationContext applicationContext;

    @Override
    public Object postProcessAfterInitialization(@NonNull Object bean, @NonNull String beanName) throws BeansException {
        if (bean instanceof CacheManager) {
            // 防止重复包装
            if (bean instanceof LingCacheManagerProxy) {
                log.debug("[LingFrame] CacheManager {} already wrapped, skipping", beanName);
                return bean;
            }

            log.info(">>>>>> [LingFrame] Protecting CacheManager: {}", beanName);
            try {
                // 劫持 CacheManager，让它吐出受控的 Cache
                PermissionService permissionService = applicationContext.getBean(PermissionService.class);
                return new LingCacheManagerProxy((CacheManager) bean, permissionService);
            } catch (Exception e) {
                log.error("Failed to wrap CacheManager: {}", e.getMessage(), e);
            }
        }
        return bean;
    }

    @Override
    public Object postProcessBeforeInitialization(@NonNull Object bean, @NonNull String beanName)
            throws BeansException {
        return bean;
    }

    @Override
    public void setApplicationContext(@NonNull ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }
}
