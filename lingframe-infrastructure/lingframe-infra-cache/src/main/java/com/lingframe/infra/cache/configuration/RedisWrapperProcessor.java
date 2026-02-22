package com.lingframe.infra.cache.configuration;

import com.lingframe.api.security.PermissionService;
import com.lingframe.infra.cache.interceptor.RedisPermissionInterceptor;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.RedisTemplate;

@Slf4j
// ✅ 技术栈探测：灵核有 RedisTemplate 类
@ConditionalOnClass(RedisTemplate.class)
// ✅ 核心强制：框架开启即生效
@ConditionalOnProperty(prefix = "lingframe", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RedisWrapperProcessor implements BeanPostProcessor, ApplicationContextAware {

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