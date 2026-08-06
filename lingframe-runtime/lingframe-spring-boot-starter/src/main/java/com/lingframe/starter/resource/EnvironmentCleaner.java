package com.lingframe.starter.resource;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.AbstractApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MutablePropertySources;

/**
 * �灵元 Context 活跃期预清理：Environment PropertySources + EventMulticaster retrieverCache。
 * <p>
 * 这些清理对象都在灵元 Context 内部，随 Context 销毁自然释放，
 * 不阻止 ClassLoader GC，但避免 Context close 过程中残留引用。
 */
@Slf4j
final class EnvironmentCleaner {

    /** 清理灵元 Environment 的所有 PropertySources */
    void clean(String lingId, ConfigurableApplicationContext lingContext) {
        cleanSpringEnvironment(lingId, lingContext);
        cleanRetrieverCache(lingId, lingContext);
    }

    private void cleanSpringEnvironment(String lingId, ConfigurableApplicationContext lingContext) {
        try {
            Environment rawEnv = lingContext.getEnvironment();
            if (rawEnv instanceof ConfigurableEnvironment) {
                ConfigurableEnvironment env = (ConfigurableEnvironment) rawEnv;
                MutablePropertySources sources = env.getPropertySources();
                List<String> names = new ArrayList<>();
                sources.forEach(ps -> names.add(ps.getName()));
                names.forEach(sources::remove);
                log.info("[{}] Cleared {} PropertySources from ling Environment", lingId, names.size());
            }
        } catch (Exception e) {
            log.debug("[{}] Failed to clear PropertySources: {}", lingId, e.getMessage());
        }
    }

    private void cleanRetrieverCache(String lingId, ConfigurableApplicationContext lingContext) {
        try {
            Object multicaster = lingContext
                    .getBean(AbstractApplicationContext.APPLICATION_EVENT_MULTICASTER_BEAN_NAME);
            if (multicaster == null)
                return;
            // 沿继承链查找 retrieverCache（兼容 Spring 5.x / 6.x）
            Field retrieverCacheField = SpringCleanupSupport.findFieldInHierarchy(
                    multicaster.getClass(), "retrieverCache");
            if (retrieverCacheField != null) {
                retrieverCacheField.setAccessible(true);
                Object cache = retrieverCacheField.get(multicaster);
                if (cache instanceof Map<?, ?>) {
                    int size = ((Map<?, ?>) cache).size();
                    ((Map<?, ?>) cache).clear();
                    log.debug("[{}] Cleared retrieverCache ({} entries)", lingId, size);
                }
            } else {
                log.trace("[{}] retrieverCache field not found", lingId);
            }
        } catch (NoSuchBeanDefinitionException e) {
            log.trace("[{}] No ApplicationEventMulticaster bean found", lingId);
        } catch (Exception e) {
            log.debug("[{}] Failed to clear retrieverCache: {}", lingId, e.getMessage());
        }
    }
}
