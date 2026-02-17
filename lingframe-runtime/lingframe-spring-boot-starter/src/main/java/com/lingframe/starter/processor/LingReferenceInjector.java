package com.lingframe.starter.processor;

import com.lingframe.api.annotation.LingReference;
import com.lingframe.core.plugin.PluginManager;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Field;

@Slf4j
public class LingReferenceInjector implements BeanPostProcessor, ApplicationContextAware {

    private final String currentPluginId; // 记录当前环境的插件ID
    private ApplicationContext applicationContext;
    private PluginManager pluginManager; // 懒加载

    public LingReferenceInjector(String currentPluginId) {
        this.currentPluginId = currentPluginId;
    }

    // 兼容旧构造函数（插件内部使用）
    public LingReferenceInjector(String currentPluginId, PluginManager pluginManager) {
        this.currentPluginId = currentPluginId;
        this.pluginManager = pluginManager;
    }

    @Override
    public void setApplicationContext(@NonNull ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }

    /**
     * 懒加载获取 PluginManager
     */
    private PluginManager getPluginManager() {
        if (pluginManager == null && applicationContext != null) {
            try {
                pluginManager = applicationContext.getBean(PluginManager.class);
            } catch (Exception e) {
                log.debug("PluginManager not available yet");
            }
        }
        return pluginManager;
    }

    /**
     * 确保在 AOP 代理创建之前，把属性注入到原始对象(Target)中。
     */
    @Override
    public Object postProcessBeforeInitialization(Object bean, @NonNull String beanName) throws BeansException {
        PluginManager pm = getPluginManager();
        if (pm == null) {
            return bean; // PluginManager 未准备好，跳过
        }

        Class<?> clazz = bean.getClass();

        // 递归处理所有字段 (包括父类)
        ReflectionUtils.doWithFields(clazz, field -> {
            LingReference annotation = field.getAnnotation(LingReference.class);
            if (annotation != null) {
                injectService(bean, field, annotation, pm);
            }
        });

        return bean;
    }

    // postProcessAfterInitialization 保持默认（直接返回 bean）即可，或者不重写
    @Override
    public Object postProcessAfterInitialization(@NonNull Object bean, @NonNull String beanName) throws BeansException {
        return bean;
    }

    private void injectService(Object bean, Field field, LingReference annotation, PluginManager pm) {
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
            Object proxy = pm.getGlobalServiceProxy(
                    callerId,
                    serviceType,
                    targetPluginId);
            field.set(bean, proxy);
            log.info("Injected @LingReference for field: {}.{}",
                    bean.getClass().getSimpleName(), field.getName());
        } catch (IllegalAccessException e) {
            log.error("Failed to inject @LingReference", e);
        }
    }
}
