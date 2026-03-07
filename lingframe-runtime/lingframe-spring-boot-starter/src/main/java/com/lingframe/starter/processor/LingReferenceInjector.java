package com.lingframe.starter.processor;

import com.lingframe.api.annotation.LingReference;
import com.lingframe.api.context.LingContext;
import com.lingframe.api.exception.LingInvocationException;
import com.lingframe.api.exception.LingRuntimeException;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;

@Slf4j
public class LingReferenceInjector implements BeanPostProcessor, ApplicationContextAware {

    private final String currentLingId; // 记录当前环境的灵元ID
    private ApplicationContext applicationContext;
    private LingContext lingContext; // 懒加载

    public LingReferenceInjector(String currentLingId) {
        this.currentLingId = currentLingId;
    }

    // 兼容旧构造函数（灵元内部使用）
    public LingReferenceInjector(String currentLingId, LingContext lingContext) {
        this.currentLingId = currentLingId;
        this.lingContext = lingContext;
    }

    @Override
    public void setApplicationContext(@NonNull ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }

    /**
     * 懒加载获取 LingContext
     */
    private LingContext getLingContext() {
        if (lingContext == null && applicationContext != null) {
            try {
                lingContext = applicationContext.getBean(LingContext.class);
            } catch (Exception e) {
                log.debug("LingContext not available yet");
            }
        }
        return lingContext;
    }

    /**
     * 确保在 AOP 代理创建之前，把属性注入到原始对象(Target)中。
     */
    @Override
    public Object postProcessBeforeInitialization(Object bean, @NonNull String beanName) throws BeansException {
        LingContext ctx = getLingContext();
        if (ctx == null) {
            return bean; // LingContext 未准备好，跳过
        }

        Class<?> clazz = bean.getClass();

        // 递归处理所有字段 (包括父类)
        ReflectionUtils.doWithFields(clazz, field -> {
            LingReference annotation = field.getAnnotation(LingReference.class);
            if (annotation != null) {
                injectService(bean, field, annotation, ctx);
            }
        });

        return bean;
    }

    // postProcessAfterInitialization 保持默认（直接返回 bean）即可，或者不重写
    @Override
    public Object postProcessAfterInitialization(@NonNull Object bean, @NonNull String beanName) throws BeansException {
        return bean;
    }

    private void injectService(Object bean, Field field, LingReference annotation, LingContext ctx) {
        try {
            field.setAccessible(true);

            // 【防御】如果字段已经有值（比如被 XML 配置或 @Autowired 填充），则跳过
            if (field.get(bean) != null) {
                log.debug("Field {} is already injected, skipping LingReference injection.", field.getName());
                return;
            }

            Class<?> serviceType = field.getType();
            // 在 V0.3.0 之后，目标路由交由底层 PipelineEngine 与 Context 内置的 GlobalServiceRoutingProxy
            // 自动抉择
            Object proxy = ctx.getService(serviceType).orElseThrow(() -> new LingRuntimeException(currentLingId,
                    "Failed to resolve service reference for type: " + serviceType.getName()));

            // 【增强】对 @LingReference 开启声明式 Fallback 包装支持
            Class<?> fallbackClass = annotation.fallback();
            if (fallbackClass != void.class) {
                final Object originalProxy = proxy;
                proxy = Proxy.newProxyInstance(
                        serviceType.getClassLoader(),
                        new Class<?>[] { serviceType },
                        (p, method, methodArgs) -> {
                            try {
                                return method.invoke(originalProxy, methodArgs);
                            } catch (InvocationTargetException e) {
                                Throwable cause = e.getCause();
                                // 如果捕获到底层抛出的跨组件熔断/超时异常
                                if (cause instanceof LingInvocationException) {
                                    if (applicationContext != null) {
                                        try {
                                            Object fallbackInstance = applicationContext.getBean(fallbackClass);
                                            log.warn(
                                                    "[Fallback] LingReference triggered fallback for target {} to instance {}",
                                                    serviceType.getSimpleName(), fallbackClass.getSimpleName());
                                            return method.invoke(fallbackInstance, methodArgs);
                                        } catch (Exception fallbackEx) {
                                            log.error("Failed to execute fallback logic or get fallback bean",
                                                    fallbackEx);
                                        }
                                    }
                                }
                                // 若非异常降级场景或降级自身报错，继续向上抛出真实异常
                                throw cause;
                            }
                        });
            }

            field.set(bean, proxy);
            log.info("Injected @LingReference for field: {}.{}",
                    bean.getClass().getSimpleName(), field.getName());
        } catch (IllegalAccessException e) {
            log.error("Failed to inject @LingReference", e);
        }
    }
}
