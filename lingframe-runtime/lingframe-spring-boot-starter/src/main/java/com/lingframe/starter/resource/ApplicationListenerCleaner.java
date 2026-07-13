package com.lingframe.starter.resource;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.ApplicationEventMulticaster;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * ApplicationListener清理器：清理灵元注册的监听器，防止引用泄漏。
 * <p>
 * Spring Boot在启动灵元ApplicationContext时，会自动注册监听器到灵核容器的事件系统中，
 * 例如 ParentContextCloserApplicationListener、ConditionEvaluationReportLoggingListener等。
 * 这些监听器持有灵元ApplicationContext引用，必须在卸载时清理。
 */
@Slf4j
class ApplicationListenerCleaner {

    /**
     * 清理灵元注册的所有ApplicationListener。
     *
     * @param lingId 灵元ID
     * @param mainContext 灵核ApplicationContext
     * @param lingContext 灵元ApplicationContext
     */
    void clear(String lingId, ApplicationContext mainContext, ConfigurableApplicationContext lingContext) {
        if (mainContext == null || lingContext == null) {
            log.debug("[{}] Skipping ApplicationListener cleanup: context is null", lingId);
            return;
        }

        try {
            // 获取灵核容器的事件广播器
            ApplicationEventMulticaster multicaster = mainContext.getBean(
                    ApplicationEventMulticaster.class);

            // 反射获取监听器列表（AbstractApplicationEventMulticaster的内部结构）
            Field defaultRetrieverField = multicaster.getClass().getDeclaredField("defaultRetriever");
            defaultRetrieverField.setAccessible(true);
            Object defaultRetriever = defaultRetrieverField.get(multicaster);

            // 收集需要移除的监听器
            List<ApplicationListener<?>> listenersToRemove = new ArrayList<>();

            if (defaultRetriever != null) {
                // 遍历 defaultRetriever 的所有字段，找到监听器集合
                Field[] retrieverFields = defaultRetriever.getClass().getDeclaredFields();
                for (Field field : retrieverFields) {
                    field.setAccessible(true);
                    Object fieldValue = field.get(defaultRetriever);

                    if (fieldValue instanceof Set) {
                        Set<ApplicationListener<?>> listeners = (Set<ApplicationListener<?>>) fieldValue;
                        for (ApplicationListener<?> listener : listeners) {
                            if (shouldRemove(listener, lingContext)) {
                                listenersToRemove.add(listener);
                            }
                        }
                    } else if (fieldValue instanceof List) {
                        List<ApplicationListener<?>> listeners = (List<ApplicationListener<?>>) fieldValue;
                        for (ApplicationListener<?> listener : listeners) {
                            if (shouldRemove(listener, lingContext)) {
                                listenersToRemove.add(listener);
                            }
                        }
                    }
                }
            }

            // 执行移除
            for (ApplicationListener<?> listener : listenersToRemove) {
                try {
                    multicaster.removeApplicationListener(listener);
                    log.debug("[{}] Removed ApplicationListener: {}",
                            lingId, listener.getClass().getName());
                } catch (Exception e) {
                    log.warn("[{}] Failed to remove listener {}: {}",
                            lingId, listener.getClass().getName(), e.getMessage());
                }
            }

            log.info("[{}] Cleaned {} ApplicationListeners from lingcore context",
                    lingId, listenersToRemove.size());

        } catch (NoSuchFieldException e) {
            log.debug("[{}] ApplicationEventMulticaster does not have defaultRetriever field, skip cleaning", lingId);
        } catch (Exception e) {
            log.warn("[{}] Failed to clean ApplicationListeners: {}", lingId, e.getMessage());
        }
    }

    /**
     * 判断监听器是否应该被移除。
     * <p>
     * 通过反射检查监听器是否持有灵元ApplicationContext引用。
     */
    private boolean shouldRemove(ApplicationListener<?> listener, ConfigurableApplicationContext lingContext) {
        try {
            // 1. 检查监听器的Context字段（常见模式）
            Field[] fields = listener.getClass().getDeclaredFields();
            for (Field field : fields) {
                field.setAccessible(true);
                Object value = field.get(listener);
                if (value == lingContext) {
                    return true;
                }
                // 检查嵌套引用（如Lambda表达式）
                if (value != null && holdsLingContextReference(value, lingContext)) {
                    return true;
                }
            }

            // 2. 特殊处理：ParentContextCloserApplicationListener
            // Spring Boot内部类，通过parentContext字段持有引用
            if (listener.getClass().getName().contains("ParentContextCloserApplicationListener")) {
                return checkParentContextCloser(listener, lingContext);
            }

        } catch (Exception e) {
            log.debug("Failed to inspect listener {}: {}",
                    listener.getClass().getName(), e.getMessage());
        }

        return false;
    }

    /**
     * 检查对象是否持有灵元Context引用（深度检查）。
     */
    private boolean holdsLingContextReference(Object obj, ConfigurableApplicationContext lingContext) {
        try {
            // Lambda表达式可能捕获Context引用
            if (obj.getClass().getName().contains("$$Lambda$")) {
                Field[] fields = obj.getClass().getDeclaredFields();
                for (Field field : fields) {
                    field.setAccessible(true);
                    Object capturedValue = field.get(obj);
                    if (capturedValue == lingContext) {
                        return true;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    /**
     * 特殊处理：检查ParentContextCloserApplicationListener是否持有灵元Context。
     */
    private boolean checkParentContextCloser(ApplicationListener<?> listener,
                                              ConfigurableApplicationContext lingContext) {
        try {
            // ParentContextCloserApplicationListener有parentContext字段
            Field parentContextField = listener.getClass().getDeclaredField("parentContext");
            parentContextField.setAccessible(true);
            Object parentContext = parentContextField.get(listener);
            return parentContext == lingContext;
        } catch (NoSuchFieldException e) {
            // 字段不存在，可能版本变化
            log.debug("ParentContextCloserApplicationListener field structure changed");
        } catch (Exception e) {
            log.debug("Failed to check ParentContextCloserApplicationListener: {}", e.getMessage());
        }
        return false;
    }
}