package com.lingframe.starter.processor;

import com.lingframe.api.annotation.LingReference;
import com.lingframe.core.ling.LingManager;
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

    private final String currentLingId; // 记录当前环境的单元ID
    private ApplicationContext applicationContext;
    private LingManager lingManager; // 懒加载

    public LingReferenceInjector(String currentLingId) {
        this.currentLingId = currentLingId;
    }

    // 兼容旧构造函数（单元内部使用）
    public LingReferenceInjector(String currentLingId, LingManager lingManager) {
        this.currentLingId = currentLingId;
        this.lingManager = lingManager;
    }

    @Override
    public void setApplicationContext(@NonNull ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }

    /**
     * 懒加载获取 LingManager
     */
    private LingManager getLingManager() {
        if (lingManager == null && applicationContext != null) {
            try {
                lingManager = applicationContext.getBean(LingManager.class);
            } catch (Exception e) {
                log.debug("LingManager not available yet");
            }
        }
        return lingManager;
    }

    /**
     * 确保在 AOP 代理创建之前，把属性注入到原始对象(Target)中。
     */
    @Override
    public Object postProcessBeforeInitialization(Object bean, @NonNull String beanName) throws BeansException {
        LingManager pm = getLingManager();
        if (pm == null) {
            return bean; // LingManager 未准备好，跳过
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

    private void injectService(Object bean, Field field, LingReference annotation, LingManager pm) {
        try {
            field.setAccessible(true);

            // 【防御】如果字段已经有值（比如被 XML 配置或 @Autowired 填充），则跳过
            if (field.get(bean) != null) {
                log.debug("Field {} is already injected, skipping LingReference injection.", field.getName());
                return;
            }

            Class<?> serviceType = field.getType();
            String targetLingId = annotation.lingId();
            // 🔥使用构造函数传入的 currentLingId，而不是写死或猜
            String callerId = (currentLingId != null) ? currentLingId : "lingcore-app";

            // 创建全局路由代理
            Object proxy = pm.getGlobalServiceProxy(
                    callerId,
                    serviceType,
                    targetLingId);
            field.set(bean, proxy);
            log.info("Injected @LingReference for field: {}.{}",
                    bean.getClass().getSimpleName(), field.getName());
        } catch (IllegalAccessException e) {
            log.error("Failed to inject @LingReference", e);
        }
    }
}
