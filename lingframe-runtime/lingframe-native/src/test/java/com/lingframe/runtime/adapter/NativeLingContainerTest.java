package com.lingframe.runtime.adapter;

import com.lingframe.api.annotation.LingService;
import com.lingframe.api.context.LingContext;
import com.lingframe.api.event.LingEvent;
import com.lingframe.api.ling.Ling;
import com.lingframe.api.security.PermissionService;
import com.lingframe.core.context.DefaultLingContext;
import com.lingframe.core.event.EventBus;
import com.lingframe.core.ling.DefaultLingRepository;
import com.lingframe.core.ling.DefaultLingServiceRegistry;
import com.lingframe.core.ling.InvokableMethodCache;
import com.lingframe.core.pipeline.FilterRegistry;
import com.lingframe.core.pipeline.InvocationPipelineEngine;
import com.lingframe.core.pipeline.LatestVersionPolicy;
import com.lingframe.core.security.DefaultPermissionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("NativeLingContainer 测试")
class NativeLingContainerTest {

    @Test
    @DisplayName("DefaultLingContext 下应扫描并注册 @LingService")
    void shouldRegisterLingServicesWhenUsingDefaultLingContext() throws Exception {
        EventBus eventBus = new EventBus();
        DefaultLingRepository repository = new DefaultLingRepository();
        DefaultLingServiceRegistry registry = new DefaultLingServiceRegistry();
        DefaultPermissionService permissionService = new DefaultPermissionService(eventBus);
        FilterRegistry filterRegistry = new FilterRegistry(new InvokableMethodCache(), permissionService);
        filterRegistry.initialize(repository, new LatestVersionPolicy(), eventBus);
        InvocationPipelineEngine pipelineEngine = new InvocationPipelineEngine(filterRegistry);
        LingContext context = new DefaultLingContext("native-ling", repository, registry, pipelineEngine, permissionService, eventBus);

        NativeLingContainer container = new NativeLingContainer(
                "native-ling",
                TestNativeLing.class,
                TestNativeLing.class.getClassLoader(),
                new File("E:\\Codes\\灵珑\\LingFrame\\lingframe-runtime\\lingframe-native\\target\\test-classes"));

        try {
            container.start(context);

            assertTrue(registry.hasMethod("native-ling:native.echo", "echo", new String[0]));
            assertTrue(TestNativeLing.started.get());
        } finally {
            container.stop();
        }
    }

    @Test
    @DisplayName("非 DefaultLingContext 下应跳过服务注册而不抛异常")
    void shouldSkipServiceRegistrationWhenContextIsNotDefaultLingContext() {
        LingContext context = new MinimalLingContext("native-ling");

        NativeLingContainer container = new NativeLingContainer(
                "native-ling",
                TestNativeLing.class,
                TestNativeLing.class.getClassLoader(),
                new File("E:\\Codes\\灵珑\\LingFrame\\lingframe-runtime\\lingframe-native\\target\\test-classes"));

        try {
            container.start(context);
            assertTrue(TestNativeLing.started.get());
        } finally {
            container.stop();
        }
    }

    private static class MinimalLingContext implements LingContext {
        private final String lingId;

        private MinimalLingContext(String lingId) {
            this.lingId = lingId;
        }

        @Override
        public String getLingId() {
            return lingId;
        }

        @Override
        public Optional<String> getProperty(String key) {
            return Optional.empty();
        }

        @Override
        public <T> Optional<T> invoke(String serviceId, Object... args) {
            return Optional.empty();
        }

        @Override
        public <T> T invokeOrDefault(String serviceId, T defaultValue, Object... args) {
            return defaultValue;
        }

        @Override
        public <T> T invokeOrElse(String serviceId, Supplier<T> fallbackSupplier, Object... args) {
            return fallbackSupplier.get();
        }

        @Override
        public <T> Optional<T> getService(Class<T> serviceClass) {
            return Optional.empty();
        }

        @Override
        public PermissionService getPermissionService() {
            return null;
        }

        @Override
        public void publishEvent(LingEvent event) {
            // no-op
        }
    }

    public static class TestNativeLing implements Ling {
        static final AtomicBoolean started = new AtomicBoolean(false);

        @Override
        public void onStart(LingContext context) {
            started.set(true);
        }

        @Override
        public void onStop(LingContext context) {
            started.set(false);
        }

        @LingService(id = "native.echo")
        public String echo() {
            return "ok";
        }
    }
}
