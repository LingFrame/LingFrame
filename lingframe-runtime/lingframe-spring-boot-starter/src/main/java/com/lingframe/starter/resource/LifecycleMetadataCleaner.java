package com.lingframe.starter.resource;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 清理灵核 BeanFactory 中 BeanPostProcessor 的生命周期元数据缓存。
 * <p>
 * BPP 缓存 key 是灵元加载的 Class，会阻止灵元 ClassLoader GC。
 * 同时清理 Spring Cache 全局 AOP 的 attributeCache。
 */
@Slf4j
final class LifecycleMetadataCleaner {

    void clear(String lingId,
            ConfigurableListableBeanFactory beanFactory,
            ClassLoader lingClassLoader) {
        if (!(beanFactory instanceof DefaultListableBeanFactory))
            return;

        clearBppCaches(lingId, beanFactory, lingClassLoader);
        clearCacheOperationSourceAttributeCache(lingId, beanFactory, lingClassLoader);
    }

    private void clearBppCaches(String lingId,
            ConfigurableListableBeanFactory beanFactory,
            ClassLoader lingClassLoader) {
        try {
            List<BeanPostProcessor> bpps = getBeanPostProcessors(beanFactory);
            if (bpps == null || bpps.isEmpty())
                return;

            String[] cacheFieldNames = {
                    "lifecycleMetadataCache",
                    "injectionMetadataCache",
                    "eligibleBeans"
            };

            for (BeanPostProcessor bpp : bpps) {
                for (String fieldName : cacheFieldNames) {
                    Field f = SpringCleanupSupport.findFieldInHierarchy(bpp.getClass(), fieldName);
                    if (f == null)
                        continue;
                    try {
                        f.setAccessible(true);
                        Object cache = f.get(bpp);
                        if (!(cache instanceof Map<?, ?>))
                            continue;
                        Map<?, ?> map = (Map<?, ?>) cache;
                        int before = map.size();
                        // 精确移除：只移除目标 ClassLoader 相关的条目
                        map.entrySet().removeIf(entry -> {
                            try {
                                Object key = entry.getKey();
                                if (key instanceof Class<?>) {
                                    return ((Class<?>) key).getClassLoader() == lingClassLoader;
                                }
                                if (key instanceof String) {
                                    return SpringCleanupSupport.isValueRelatedToClassLoader(entry.getValue(), lingClassLoader);
                                }
                                return SpringCleanupSupport.isRelatedToClassLoader(key, lingClassLoader)
                                        || SpringCleanupSupport.isValueRelatedToClassLoader(entry.getValue(), lingClassLoader);
                            } catch (Exception e) {
                                return false; // 严防迭代异常中断整个清扫逻辑
                            }
                        });
                        int removed = before - map.size();
                        if (removed > 0) {
                            log.debug("[{}] {}.{}: removed {} entries",
                                    lingId, bpp.getClass().getSimpleName(), fieldName, removed);
                        }
                    } catch (Exception ignored) {
                    }
                }
            }
        } catch (Exception e) {
            log.debug("[{}] Lifecycle metadata cleanup failed: {}", lingId, e.getMessage());
        }
    }

    private void clearCacheOperationSourceAttributeCache(String lingId,
            ConfigurableListableBeanFactory beanFactory,
            ClassLoader lingClassLoader) {
        try {
            for (String beanName : beanFactory.getBeanDefinitionNames()) {
                try {
                    // 🔥 改用 getSingleton 而非 getBean：卸载阶段只清理已实例化的单例，
                    // 绝不触发懒加载初始化，否则会让原本未启动的 Bean 在卸载时被强行创建，
                    // 既浪费资源又会引入新的 ClassLoader 引用。
                    Object bean = beanFactory.getSingleton(beanName);
                    if (bean == null) continue;

                    Object targetToClear = null;
                    // 1. 顶层 CacheOperationSource
                    if (bean.getClass().getName().contains("AnnotationCacheOperationSource")) {
                        targetToClear = bean;
                    }
                    // 2. 寄生 Advisor / Interceptor，探测内部 cacheOperationSource
                    else if (bean.getClass().getName().contains("CacheOperationSourceAdvisor")
                            || bean.getClass().getName().contains("CacheInterceptor")) {
                        Field sourceField = SpringCleanupSupport.findFieldInHierarchy(bean.getClass(), "cacheOperationSource");
                        if (sourceField != null) {
                            sourceField.setAccessible(true);
                            targetToClear = sourceField.get(bean);
                        }
                    }

                    if (targetToClear != null
                            && targetToClear.getClass().getName().contains("AnnotationCacheOperationSource")) {
                        Field attributeCacheField = SpringCleanupSupport.findFieldInHierarchy(
                                targetToClear.getClass(), "attributeCache");
                        if (attributeCacheField != null) {
                            attributeCacheField.setAccessible(true);
                            Object cache = attributeCacheField.get(targetToClear);
                            if (cache instanceof Map<?, ?>) {
                                Map<?, ?> map = (Map<?, ?>) cache;
                                int before = map.size();
                                map.entrySet().removeIf(entry -> {
                                    try {
                                        return SpringCleanupSupport.isRelatedToClassLoader(entry.getKey(), lingClassLoader)
                                                || SpringCleanupSupport.isValueRelatedToClassLoader(entry.getValue(), lingClassLoader);
                                    } catch (Exception e) {
                                        return false;
                                    }
                                });
                                int removed = before - map.size();
                                if (removed > 0) {
                                    log.debug("[{}] {}.attributeCache: removed {} entries",
                                            lingId, targetToClear.getClass().getSimpleName(), removed);
                                }
                            }
                        }
                    }
                } catch (Exception ignored) {
                }
            }
        } catch (Exception e) {
            log.debug("[{}] CacheOperationSource metadata cleanup failed: {}", lingId, e.getMessage());
        }
    }

    /**
     * 获取 BeanPostProcessor 列表。
     * Spring 5.x：AbstractBeanFactory.beanPostProcessors 通常是 List；
     * Spring 6.x：可能封装为 BeanPostProcessorCache 内部类。
     */
    @SuppressWarnings("unchecked")
    private List<BeanPostProcessor> getBeanPostProcessors(ConfigurableListableBeanFactory beanFactory) {
        String[] possibleFieldNames = { "beanPostProcessors", "beanPostProcessorCache" };
        for (String fieldName : possibleFieldNames) {
            Field f = SpringCleanupSupport.findFieldInHierarchy(beanFactory.getClass(), fieldName);
            if (f == null)
                continue;
            try {
                f.setAccessible(true);
                Object val = f.get(beanFactory);
                if (val instanceof List) {
                    return (List<BeanPostProcessor>) val;
                }
                if (val != null) {
                    // Spring 6.x BeanPostProcessorCache：提取所有 List<BPP> 字段
                    List<BeanPostProcessor> result = new ArrayList<>();
                    for (Field inner : val.getClass().getDeclaredFields()) {
                        if (List.class.isAssignableFrom(inner.getType())) {
                            try {
                                inner.setAccessible(true);
                                Object list = inner.get(val);
                                if (list instanceof List) {
                                    result.addAll((List<BeanPostProcessor>) list);
                                }
                            } catch (Exception ignored) {
                            }
                        }
                    }
                    if (!result.isEmpty())
                        return result;
                }
            } catch (Exception ignored) {
            }
        }
        // 兜底：公开 API
        try {
            if (beanFactory instanceof DefaultListableBeanFactory) {
                String[] names = ((DefaultListableBeanFactory) beanFactory)
                        .getBeanNamesForType(BeanPostProcessor.class, true, false);
                List<BeanPostProcessor> result = new ArrayList<>();
                for (String name : names) {
                    try {
                        result.add(beanFactory.getBean(name, BeanPostProcessor.class));
                    } catch (Exception ignored) {
                    }
                }
                return result;
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}
