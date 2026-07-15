package com.lingframe.infra.cache.spring;

import com.lingframe.api.security.PermissionService;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeansException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.CacheManager;

@Slf4j
// ✅ 只要有 CacheManager 类就加载，不管底层实现是啥
@ConditionalOnClass(CacheManager.class)
@ConditionalOnProperty(prefix = "lingframe", name = "enabled", havingValue = "true", matchIfMissing = true)
public class SpringCacheWrapperProcessor extends AbstractGovernanceWrapperProcessor<CacheManager> {

    @Override
    protected String getBeanTypeDescription() {
        return "CacheManager bean";
    }

    @Override
    public Object postProcessAfterInitialization(@NonNull Object bean, @NonNull String beanName) throws BeansException {
        // 防重包装：已经被本代理包装过的 CacheManager 不再重复包装
        if (bean instanceof LingCacheManagerProxy) {
            return bean;
        }
        if (bean instanceof CacheManager) {
            PermissionService permissionService = resolvePermissionService(beanName);
            if (permissionService == null) {
                // 治理未启用（PermissionService bean 未注册）：跳过包装
                return bean;
            }
            log.info("[LingFrame] Protecting CacheManager: {}", beanName);
            // 劫持 CacheManager，让它吐出受控的 Cache
            return new LingCacheManagerProxy((CacheManager) bean, permissionService);
        }
        return bean;
    }
}
