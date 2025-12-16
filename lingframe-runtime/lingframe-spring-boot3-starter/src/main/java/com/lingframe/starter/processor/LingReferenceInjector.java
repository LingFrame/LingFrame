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

    @Override
    public Object postProcessAfterInitialization(Object bean, @NonNull String beanName) throws BeansException {
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

    private void injectService(Object bean, Field field, LingReference annotation) {
        Class<?> serviceType = field.getType();
        String targetPluginId = annotation.pluginId();

        // 创建全局路由代理
        // 这里的 callerPluginId 先硬编码为 "host-app"，实际可以做得更细
        Object proxy = pluginManager.getGlobalServiceProxy(
                "host-app",
                serviceType,
                targetPluginId
        );

        try {
            field.setAccessible(true);
            field.set(bean, proxy);
            log.info("Injected @LingReference for field: {}.{}",
                    bean.getClass().getSimpleName(), field.getName());
        } catch (IllegalAccessException e) {
            log.error("Failed to inject @LingReference", e);
        }
    }
}