package com.lingframe.starter.processor;

import com.lingframe.api.annotation.LingReference;
import com.lingframe.core.plugin.PluginManager;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Field;

@Slf4j
@RequiredArgsConstructor
public class LingReferenceInjector implements BeanPostProcessor {

    private final PluginManager pluginManager;

    private final String currentPluginId; // 🔥记录当前环境的插件ID

    /**
     * 从 postProcessAfterInitialization 改为 postProcessBeforeInitialization
     * 确保在 AOP 代理创建之前，把属性注入到原始对象(Target)中。
     */
    @Override
    public Object postProcessBeforeInitialization(Object bean, @NonNull String beanName) throws BeansException {
        Class<?> clazz = bean.getClass();

        // 递归处理所有字段 (包括父类)
        ReflectionUtils.doWithFields(clazz, field -> {
            LingReference annotation = field.getAnnotation(LingReference.class);
            if (annotation != null) {
                injectService(bean, field, annotation);
            }
        });

        return bean;
    }

    // postProcessAfterInitialization 保持默认（直接返回 bean）即可，或者不重写
    @Override
    public Object postProcessAfterInitialization(@NonNull Object bean, @NonNull String beanName) throws BeansException {
        return bean;
    }

    private void injectService(Object bean, Field field, LingReference annotation) {
        try {
            field.setAccessible(true);

            // 【防御】如果字段已经有值（比如被 XML 配置或 @Autowired 填充），则跳过
            if (field.get(bean) != null) {
                log.debug("Field {} is already injected, skipping LingReference injection.", field.getName());
                return;
            }

            Class<?> serviceType = field.getType();
            String targetPluginId = annotation.pluginId();
            // 🔥使用构造函数传入的 currentPluginId，而不是写死或猜
            String callerId = (currentPluginId != null) ? currentPluginId : "host-app";

            // 创建全局路由代理
            // 这里的 callerPluginId 先硬编码为 "host-app"，实际可以做得更细
            Object proxy = pluginManager.getGlobalServiceProxy(
                    callerId,
                    serviceType,
                    targetPluginId
            );
            field.set(bean, proxy);
            log.info("Injected @LingReference for field: {}.{}",
                    bean.getClass().getSimpleName(), field.getName());
        } catch (IllegalAccessException e) {
            log.error("Failed to inject @LingReference", e);
        }
    }
}