package com.lingframe.starter.resource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("SpringBasicResourceGuard 测试")
class SpringBasicResourceGuardTest {

    @Test
    @DisplayName("应清理 Spring Boot shutdown hook 中持有的灵元上下文引用")
    void shouldClearLingReferencesFromSpringBootShutdownHookHolders() throws Exception {
        SpringBasicResourceGuard guard = new SpringBasicResourceGuard();
        ClassLoader lingClassLoader = new ClassLoader(null) {
        };

        GenericApplicationContext lingContext = new GenericApplicationContext();
        lingContext.setClassLoader(lingClassLoader);
        guard.setContexts(null, lingContext);

        GenericApplicationContext otherContext = new GenericApplicationContext();
        Object wrapper = new ShutdownHookWrapper(lingContext);

        ShutdownHookHolder holder = new ShutdownHookHolder();
        holder.contexts.add(lingContext);
        holder.contexts.add(wrapper);
        holder.contexts.add(otherContext);
        holder.handlers.put("ling", lingContext);
        holder.handlers.put("wrapper", wrapper);
        holder.handlers.put("other", otherContext);

        Method method = SpringBasicResourceGuard.class.getDeclaredMethod(
                "removeShutdownHookTargetReferences",
                String.class,
                String.class,
                Class.class,
                Object.class,
                boolean.class,
                ClassLoader.class);
        method.setAccessible(true);

        int removed = (Integer) method.invoke(
                guard,
                "order-ling",
                "test-holder",
                holder.getClass(),
                holder,
                false,
                lingClassLoader);

        assertEquals(4, removed);
        assertFalse(holder.contexts.contains(lingContext));
        assertFalse(holder.contexts.contains(wrapper));
        assertTrue(holder.contexts.contains(otherContext));
        assertFalse(holder.handlers.containsKey("ling"));
        assertFalse(holder.handlers.containsKey("wrapper"));
        assertEquals(otherContext, holder.handlers.get("other"));
    }

    @Test
    @DisplayName("应断开 ApplicationContext 自身持有的 shutdown hook 引用")
    void shouldClearApplicationContextShutdownHookField() throws Exception {
        SpringBasicResourceGuard guard = new SpringBasicResourceGuard();
        GenericApplicationContext lingContext = new GenericApplicationContext();
        Thread hook = new Thread(() -> {
        }, "ling-context-hook");

        Field shutdownHookField = findField(lingContext.getClass(), "shutdownHook");
        shutdownHookField.setAccessible(true);
        shutdownHookField.set(lingContext, hook);
        guard.setContexts(null, lingContext);

        Method method = SpringBasicResourceGuard.class.getDeclaredMethod(
                "clearApplicationContextShutdownHook",
                String.class);
        method.setAccessible(true);
        method.invoke(guard, "order-ling");

        assertNull(shutdownHookField.get(lingContext));
    }

    private Field findField(Class<?> type, String fieldName) throws Exception {
        Class<?> current = type;
        while (current != null && current != Object.class) {
            try {
                return current.getDeclaredField(fieldName);
            } catch (NoSuchFieldException ex) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(fieldName);
    }

    private static final class ShutdownHookHolder {
        private final Set<Object> contexts = new LinkedHashSet<>();
        private final Map<String, Object> handlers = new LinkedHashMap<>();
    }

    private static final class ShutdownHookWrapper {
        private final Object target;

        private ShutdownHookWrapper(Object target) {
            this.target = target;
        }
    }
}
