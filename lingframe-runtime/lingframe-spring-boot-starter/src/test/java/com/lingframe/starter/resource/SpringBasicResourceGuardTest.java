package com.lingframe.starter.resource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.AbstractApplicationContext;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.core.env.ConfigurableEnvironment;
import javax.sql.DataSource;
import java.util.concurrent.ExecutorService;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

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

    @Test
    @DisplayName("preCleanup 应该正确执行各个子清理逻辑而不崩溃")
    void testPreCleanup() throws Exception {
        SpringBasicResourceGuard guard = new SpringBasicResourceGuard();
        ConfigurableApplicationContext lingContext = mock(ConfigurableApplicationContext.class);
        ConfigurableListableBeanFactory beanFactory = mock(ConfigurableListableBeanFactory.class);
        ConfigurableEnvironment environment = mock(ConfigurableEnvironment.class);
        
        when(lingContext.isActive()).thenReturn(true);
        when(lingContext.getClassLoader()).thenReturn(this.getClass().getClassLoader());
        when(lingContext.getBeanFactory()).thenReturn(beanFactory);
        when(lingContext.getEnvironment()).thenReturn(environment);
        
        org.springframework.core.env.MutablePropertySources propertySources = new org.springframework.core.env.MutablePropertySources();
        when(environment.getPropertySources()).thenReturn(propertySources);
        
        // 模拟 EventMulticaster
        Object multicaster = mock(org.springframework.context.event.SimpleApplicationEventMulticaster.class);
        when(lingContext.getBean(AbstractApplicationContext.APPLICATION_EVENT_MULTICASTER_BEAN_NAME)).thenReturn(multicaster);

        // 模拟 DataSource
        String[] dsNames = {"dataSource"};
        when(beanFactory.getBeanNamesForType(DataSource.class, true, false)).thenReturn(dsNames);
        DataSource ds = mock(DataSource.class);
        when(beanFactory.getSingleton("dataSource")).thenReturn(ds);

        // 模拟 ExecutorService
        String[] executorNames = {"executor"};
        when(beanFactory.getBeanNamesForType(ExecutorService.class)).thenReturn(executorNames);
        ExecutorService executor = mock(ExecutorService.class);
        when(beanFactory.getBean("executor", ExecutorService.class)).thenReturn(executor);

        guard.setContexts(null, lingContext);
        
        assertDoesNotThrow(() -> guard.preCleanup("test-ling"));
        
        // 验证有尝试关闭 DataSource 和 Executor
        verify(beanFactory).getSingleton("dataSource");
        verify(beanFactory).getBean("executor", ExecutorService.class);
    }

    @Test
    @DisplayName("cleanup 和 clearContexts 应该清理 context 引用")
    void testCleanupAndClearContexts() {
        SpringBasicResourceGuard guard = new SpringBasicResourceGuard();
        ApplicationContext main = mock(ApplicationContext.class);
        ConfigurableApplicationContext ling = mock(ConfigurableApplicationContext.class);
        
        guard.setContexts(main, ling);
        guard.clearContexts();
        
        guard.setContexts(main, ling);
        guard.cleanup("test-ling", this.getClass().getClassLoader());
    }
}
