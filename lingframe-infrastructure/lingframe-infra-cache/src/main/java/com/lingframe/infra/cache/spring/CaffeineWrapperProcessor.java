package com.lingframe.infra.cache.spring;

import com.github.benmanes.caffeine.cache.Cache;
import com.lingframe.api.security.PermissionService;
import com.lingframe.infra.cache.proxy.LingCaffeineCacheProxy;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanCreationException;
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

            // fail-closed：PermissionService 不可用视为装配错误，让异常向上抛而非裸奔
            if (applicationContext == null) {
                throw new BeanCreationException(
                        "ApplicationContext not injected, cannot wrap Caffeine Cache: " + beanName);
            }
            PermissionService permissionService = applicationContext.getBean(PermissionService.class);
            return new LingCaffeineCacheProxy<>((Cache) bean, beanName, permissionService);
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
