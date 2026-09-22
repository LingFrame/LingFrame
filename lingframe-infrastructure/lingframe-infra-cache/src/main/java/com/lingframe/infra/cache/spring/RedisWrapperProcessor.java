package com.lingframe.infra.cache.spring;

import com.lingframe.api.security.PermissionService;
import com.lingframe.infra.cache.interceptor.RedisPermissionInterceptor;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.Advisor;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.BeansException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.RedisTemplate;

@Slf4j
// ✅ 技术栈探测：灵核有 RedisTemplate 类
@ConditionalOnClass(RedisTemplate.class)
// ✅ 核心强制：框架开启即生效
@ConditionalOnProperty(prefix = "lingframe", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RedisWrapperProcessor extends AbstractGovernanceWrapperProcessor<RedisTemplate> {

    @Override
    protected String getBeanTypeDescription() {
        return "RedisTemplate bean";
    }

    @Override
    public Object postProcessAfterInitialization(@NonNull Object bean, @NonNull String beanName) throws BeansException {
        // 如果 Bean 是 RedisTemplate，就把它包一层
        if (bean instanceof RedisTemplate) {
            // 防重包装：已添加 RedisPermissionInterceptor 的代理不再重复包装，避免鉴权叠加
            if (isAlreadyWrapped(bean)) {
                return bean;
            }
            PermissionService permissionService = resolvePermissionService(beanName);
            if (permissionService == null) {
                // 治理未启用（PermissionService bean 未注册）：跳过包装
                return bean;
            }
            log.info("[LingFrame] Wrapping RedisTemplate: {}", beanName);
            // 使用 ProxyFactory 创建动态代理
            ProxyFactory proxyFactory = new ProxyFactory(bean);
            proxyFactory.setProxyTargetClass(true); // 强制使用 CGLIB (保持 RedisTemplate 类型)
            proxyFactory.addAdvice(new RedisPermissionInterceptor(permissionService));

            return proxyFactory.getProxy();
        }

        return bean;
    }

    /**
     * 判断 bean 是否已经被本处理器包装过（即代理链中包含 {@link RedisPermissionInterceptor}）。
     */
    private boolean isAlreadyWrapped(Object bean) {
        if (!(bean instanceof Advised)) {
            return false;
        }
        for (Advisor advisor : ((Advised) bean).getAdvisors()) {
            if (advisor.getAdvice() instanceof RedisPermissionInterceptor) {
                return true;
            }
        }
        return false;
    }
}
