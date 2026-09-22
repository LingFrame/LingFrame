package com.lingframe.infra.cache.spring;

import com.github.benmanes.caffeine.cache.Cache;
import com.lingframe.api.security.PermissionService;
import com.lingframe.infra.cache.proxy.LingCaffeineCacheProxy;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeansException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@Slf4j
// ✅ 技术栈探测：灵核有 caffeine
@ConditionalOnClass(Cache.class)
// ✅ 核心强制：框架开启即生效
@ConditionalOnProperty(prefix = "lingframe", name = "enabled", havingValue = "true", matchIfMissing = true)
public class CaffeineWrapperProcessor extends AbstractGovernanceWrapperProcessor<Cache> {

    @Override
    protected String getBeanTypeDescription() {
        return "cache bean";
    }

    @Override
    public Object postProcessAfterInitialization(@NonNull Object bean, @NonNull String beanName) throws BeansException {
        // 防重包装：已经被本代理包装过的 bean 不再重复包装，避免双重代理导致的鉴权叠加
        if (bean instanceof LingCaffeineCacheProxy) {
            return bean;
        }
        // 如果 Bean 是 Caffeine Cache，就把它包一层
        if (bean instanceof Cache) {
            PermissionService permissionService = resolvePermissionService(beanName);
            if (permissionService == null) {
                // 治理未启用（PermissionService bean 未注册）：跳过包装
                return bean;
            }
            log.info("[LingFrame] Wrapping Caffeine Cache: {}", beanName);
            return new LingCaffeineCacheProxy<>((Cache) bean, beanName, permissionService);
        }

        return bean;
    }
}
