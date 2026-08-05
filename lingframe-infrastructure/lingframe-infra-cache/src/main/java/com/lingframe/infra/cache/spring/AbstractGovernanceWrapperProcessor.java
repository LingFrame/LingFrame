package com.lingframe.infra.cache.spring;

import com.lingframe.api.security.PermissionService;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

/**
 * 治理包装处理器抽象基类。
 * <p>
 * 提取三个 WrapperProcessor（Caffeine/Redis/SpringCache）共享的能力：
 * <ul>
 *   <li>持有 {@link ApplicationContext}，用于解析 {@link PermissionService}</li>
 *   <li>{@link #resolvePermissionService} 的 fail-closed 语义：治理启用但实例未就绪时绝不静默裸奔</li>
 * </ul>
 * 泛型参数 &lt;T&gt; 仅作意图标记，表示子类处理的 bean 类型（BeanPostProcessor 入参恒为 Object）。
 */
@Slf4j
public abstract class AbstractGovernanceWrapperProcessor<T> implements BeanPostProcessor, ApplicationContextAware {

    protected ApplicationContext applicationContext;

    /**
     * 子类处理的 bean 类型描述，用于日志和异常消息。
     * 例如 "cache bean" / "RedisTemplate bean" / "CacheManager bean"。
     */
    protected abstract String getBeanTypeDescription();

    /**
     * 解析 PermissionService。
     * <p>
     * 注意：BeanPostProcessor.postProcessAfterInitialization 对单例只回调一次，
     * 不存在"等下次回调再尝试"的语义。因此必须区分两种情况：
     * <ul>
     *   <li>(a) 治理未启用：PermissionService bean 未注册/不存在 → 返回 null，调用方跳过包装（正确）</li>
     *   <li>(b) 治理启用但实例暂未就绪 → 用 ObjectProvider.getIfAvailable() 触发初始化；
     *       若仍为 null 则 fail-closed 抛 BeanCreationException，绝不暴露未包装的 bean</li>
     * </ul>
     */
    protected PermissionService resolvePermissionService(String beanName) {
        if (applicationContext == null) {
            throw new BeanCreationException(beanName,
                    "[LingFrame] ApplicationContext not injected, cannot resolve PermissionService for "
                            + getBeanTypeDescription() + ": " + beanName);
        }
        ObjectProvider<PermissionService> provider = applicationContext.getBeanProvider(PermissionService.class);
        PermissionService permissionService = provider.getIfAvailable();
        if (permissionService != null) {
            return permissionService;
        }
        // 通过 bean 定义判断治理是否启用（allowEagerInit=false 不触发急切初始化，避免破坏装配流程）
        String[] names = applicationContext.getBeanNamesForType(PermissionService.class, false, false);
        if (names == null || names.length == 0) {
            // (a) 治理未启用：PermissionService 未注册
            log.debug("[LingFrame] PermissionService bean not registered, governance disabled, skip wrapping {}: {}",
                    getBeanTypeDescription(), beanName);
            return null;
        }
        // (b) 治理启用但实例未就绪：fail-closed，绝不静默裸奔
        throw new BeanCreationException(beanName,
                "[LingFrame] Governance enabled but PermissionService not available, refusing to expose unwrapped "
                        + getBeanTypeDescription() + ": " + beanName);
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
