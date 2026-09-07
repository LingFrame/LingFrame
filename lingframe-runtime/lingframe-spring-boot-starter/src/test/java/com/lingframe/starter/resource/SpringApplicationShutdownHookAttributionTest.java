package com.lingframe.starter.resource;

import com.lingframe.api.context.LingContext;
import com.lingframe.core.config.LingFrameConfig;
import com.lingframe.starter.adapter.SpringLingContainer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.io.DefaultResourceLoader;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.test.context.TestExecutionListeners;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("SpringApplicationShutdownHook 归因回归测试")
@TestExecutionListeners(listeners = LingFrameConfigResetListener.class,
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS)
class SpringApplicationShutdownHookAttributionTest {

    @AfterEach
    void clearShutdownHookState() throws Exception {
        Method resetMethod = bootShutdownHook().getClass().getDeclaredMethod("reset");
        resetMethod.setAccessible(true);
        resetMethod.invoke(bootShutdownHook());
        LingFrameConfig.clear();
    }

    @Test
    @DisplayName("关闭 registerShutdownHook 的普通应用不应登记到 Boot 全局 shutdown hook")
    void plainApplicationShouldNotBeTrackedWhenShutdownHookDisabled() throws Exception {
        ConfigurableApplicationContext context = new SpringApplicationBuilder(PlainApp.class)
                .web(WebApplicationType.NONE)
                .registerShutdownHook(false)
                .run();
        try {
            assertFalse(isTrackedByBootShutdownHook(context));
        } finally {
            context.close();
        }

        assertFalse(isTrackedByBootShutdownHook(context));
    }

    @Test
    @DisplayName("新增 ApplicationReadyEvent 监听器本身不会把应用登记到 Boot 全局 shutdown hook")
    void readyListenerShouldNotRegisterContextIntoBootShutdownHook() throws Exception {
        ConfigurableApplicationContext context = new SpringApplicationBuilder(ReadyListenerApp.class)
                .web(WebApplicationType.NONE)
                .registerShutdownHook(false)
                .run();
        try {
            assertFalse(isTrackedByBootShutdownHook(context));
        } finally {
            context.close();
        }

        assertFalse(isTrackedByBootShutdownHook(context));
    }

    @Test
    @DisplayName("SpringLingContainer 启动前应显式关闭日志 shutdown hook")
    void springLingContainerShouldDisableLoggingShutdownHookBeforeRun() {
        RecordingSpringApplicationBuilder builder = new RecordingSpringApplicationBuilder();
        SpringLingContainer container = new SpringLingContainer(
                builder,
                getClass().getClassLoader(),
                null,
                Collections.emptyList(),
                Collections.emptyList(),
                mock(ApplicationContext.class),
                Collections.emptyList(),
                "v1");

        LingContext lingContext = mock(LingContext.class);
        when(lingContext.getLingId()).thenReturn("order-ling");

        try {
            container.start(lingContext);
            assertTrue(builder.isLoggingShutdownHookDisabled());
        } finally {
            container.stop();
        }
    }

    @Test
    @DisplayName("SpringLingContainer 启停路径不应再把灵元类加载器挂到 Boot 全局 shutdown hook")
    void springLingContainerShouldNotLeaveClassLoaderInBootShutdownHook() throws Exception {
        URL codeSource = ContainerApp.class.getProtectionDomain().getCodeSource().getLocation();
        try (URLClassLoader lingClassLoader = new URLClassLoader(new URL[] { codeSource },
                getClass().getClassLoader())) {
            Class<?> sourceClass = lingClassLoader.loadClass(ContainerApp.class.getName());
            SpringApplicationBuilder builder = new SpringApplicationBuilder()
                    .resourceLoader(new DefaultResourceLoader(lingClassLoader))
                    .sources(sourceClass)
                    .web(WebApplicationType.NONE)
                    .registerShutdownHook(false);

            SpringLingContainer container = new SpringLingContainer(
                    builder,
                    lingClassLoader,
                    null,
                    Collections.emptyList(),
                    Collections.emptyList(),
                    mock(ApplicationContext.class),
                    Collections.emptyList(),
                    "v1");

            LingContext lingContext = mock(LingContext.class);
            when(lingContext.getLingId()).thenReturn("order-ling");

            container.start(lingContext);
            try {
                assertFalse(containsBootShutdownHookReference(lingClassLoader), findBootShutdownHookReference(lingClassLoader));
            } finally {
                container.stop();
            }

            assertFalse(containsBootShutdownHookReference(lingClassLoader), findBootShutdownHookReference(lingClassLoader));
        }
    }

    private boolean isTrackedByBootShutdownHook(ConfigurableApplicationContext context) throws Exception {
        return readContextSet("contexts").contains(context) || readContextSet("closedContexts").contains(context);
    }

    private boolean containsBootShutdownHookReference(ClassLoader targetClassLoader) throws Exception {
        return findBootShutdownHookReference(targetClassLoader) != null;
    }

    private String findBootShutdownHookReference(ClassLoader targetClassLoader) throws Exception {
        Object shutdownHook = bootShutdownHook();
        String contextsPath = findReferencePath(
                readField(shutdownHook, "contexts"),
                targetClassLoader,
                new IdentityHashMap<>(),
                5,
                "shutdownHook.contexts");
        if (contextsPath != null) {
            return contextsPath;
        }
        String closedContextsPath = findReferencePath(
                readField(shutdownHook, "closedContexts"),
                targetClassLoader,
                new IdentityHashMap<>(),
                5,
                "shutdownHook.closedContexts");
        if (closedContextsPath != null) {
            return closedContextsPath;
        }
        Object handlers = readField(shutdownHook, "handlers");
        return findReferencePath(
                readField(handlers, "actions"),
                targetClassLoader,
                new IdentityHashMap<>(),
                5,
                "shutdownHook.handlers.actions");
    }

    @SuppressWarnings("unchecked")
    private Set<ConfigurableApplicationContext> readContextSet(String fieldName) throws Exception {
        Field field = bootShutdownHook().getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return (Set<ConfigurableApplicationContext>) field.get(bootShutdownHook());
    }

    private Object readField(Object target, String fieldName) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(target);
    }

    private String findReferencePath(Object candidate,
            ClassLoader targetClassLoader,
            IdentityHashMap<Object, Boolean> visited,
            int depth,
            String path) throws Exception {
        if (candidate == null || depth <= 0) {
            return null;
        }
        if (visited.put(candidate, Boolean.TRUE) != null) {
            return null;
        }
        if (candidate == targetClassLoader) {
            return path + " -> targetClassLoader";
        }
        if (candidate instanceof ConfigurableApplicationContext) {
            ConfigurableApplicationContext context = (ConfigurableApplicationContext) candidate;
            if (context.getClassLoader() == targetClassLoader) {
                return path + " -> context.classLoader";
            }
        }
        if (candidate instanceof Class<?>) {
            return ((Class<?>) candidate).getClassLoader() == targetClassLoader
                    ? path + " -> class.classLoader(" + ((Class<?>) candidate).getName() + ")"
                    : null;
        }
        if (candidate instanceof Iterable<?>) {
            int index = 0;
            for (Object item : (Iterable<?>) candidate) {
                String childPath = findReferencePath(
                        item,
                        targetClassLoader,
                        visited,
                        depth - 1,
                        path + "[" + index + "]");
                if (childPath != null) {
                    return childPath;
                }
                index++;
            }
            return null;
        }
        if (candidate instanceof Map<?, ?>) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) candidate).entrySet()) {
                String keyPath = findReferencePath(
                        entry.getKey(),
                        targetClassLoader,
                        visited,
                        depth - 1,
                        path + "[key]");
                if (keyPath != null) {
                    return keyPath;
                }
                String valuePath = findReferencePath(
                        entry.getValue(),
                        targetClassLoader,
                        visited,
                        depth - 1,
                        path + "[value]");
                if (valuePath != null) {
                    return valuePath;
                }
            }
            return null;
        }
        if (candidate.getClass().getClassLoader() == targetClassLoader) {
            return path + " -> object.classLoader(" + candidate.getClass().getName() + ")";
        }

        Class<?> type = candidate.getClass();
        while (type != null && type != Object.class) {
            for (Field field : type.getDeclaredFields()) {
                if ((field.getModifiers() & Modifier.STATIC) != 0) {
                    continue;
                }
                field.setAccessible(true);
                Object fieldValue = field.get(candidate);
                String childPath = findReferencePath(
                        fieldValue,
                        targetClassLoader,
                        visited,
                        depth - 1,
                        path + "." + type.getSimpleName() + "." + field.getName());
                if (childPath != null) {
                    return childPath;
                }
            }
            type = type.getSuperclass();
        }
        return null;
    }

    private Object bootShutdownHook() throws Exception {
        Field field = Class.forName("org.springframework.boot.SpringApplication").getDeclaredField("shutdownHook");
        field.setAccessible(true);
        return field.get(null);
    }

    @SpringBootApplication(scanBasePackageClasses = {})
    static class PlainApp {
    }

    @SpringBootApplication(scanBasePackageClasses = {})
    static class ReadyListenerApp {
        @Bean
        ApplicationListener<ApplicationReadyEvent> readyListener() {
            return event -> {
            };
        }
    }

    @SpringBootApplication(scanBasePackageClasses = {})
    static class ContainerApp {
    }

    private static final class RecordingSpringApplicationBuilder extends SpringApplicationBuilder {

        private final List<ApplicationContextInitializer<ConfigurableApplicationContext>> initializers = new ArrayList<>();
        private boolean loggingShutdownHookDisabled;

        @Override
        public SpringApplicationBuilder properties(String... defaultProperties) {
            if (defaultProperties != null) {
                for (String property : defaultProperties) {
                    if ("logging.register-shutdown-hook=false".equals(property)) {
                        loggingShutdownHookDisabled = true;
                    }
                }
            }
            return this;
        }

        @Override
        @SuppressWarnings("unchecked")
        public SpringApplicationBuilder initializers(ApplicationContextInitializer<?>... initializers) {
            if (initializers != null) {
                for (ApplicationContextInitializer<?> initializer : initializers) {
                    this.initializers.add((ApplicationContextInitializer<ConfigurableApplicationContext>) initializer);
                }
            }
            return this;
        }

        @Override
        public ConfigurableApplicationContext run(String... args) {
            GenericApplicationContext context = new GenericApplicationContext();
            for (ApplicationContextInitializer<ConfigurableApplicationContext> initializer : initializers) {
                initializer.initialize(context);
            }
            context.refresh();
            return context;
        }

        boolean isLoggingShutdownHookDisabled() {
            return loggingShutdownHookDisabled;
        }
    }
}
