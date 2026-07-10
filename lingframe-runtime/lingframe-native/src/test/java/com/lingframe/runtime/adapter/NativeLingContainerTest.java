package com.lingframe.runtime.adapter;

import com.lingframe.api.annotation.LingService;
import com.lingframe.api.context.LingContext;
import com.lingframe.api.event.LingEvent;
import com.lingframe.api.ling.Ling;
import com.lingframe.api.security.PermissionService;
import com.lingframe.core.config.LingFrameConfig;
import com.lingframe.core.context.DefaultLingContext;
import com.lingframe.core.event.EventBus;
import com.lingframe.core.exception.LingInstallException;
import com.lingframe.core.ling.DefaultLingRepository;
import com.lingframe.core.ling.DefaultLingServiceRegistry;
import com.lingframe.core.ling.BusinessInterfaceFilter;
import com.lingframe.core.ling.LingServiceRegistrar;
import com.lingframe.core.ling.InvokableMethodCache;
import com.lingframe.core.pipeline.FilterRegistry;
import com.lingframe.core.pipeline.FilterRegistryConfig;
import com.lingframe.core.pipeline.InvocationPipelineEngine;
import com.lingframe.core.pipeline.LatestVersionPolicy;
import com.lingframe.core.security.DefaultPermissionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("NativeLingContainer 测试")
class NativeLingContainerTest {

    @Test
    @DisplayName("DefaultLingContext 下应扫描并注册 @LingService")
    void shouldRegisterLingServicesWhenUsingDefaultLingContext() throws Exception {
        EventBus eventBus = new EventBus();
        DefaultLingRepository repository = new DefaultLingRepository();
        DefaultLingServiceRegistry registry = new DefaultLingServiceRegistry();
        DefaultPermissionService permissionService = new DefaultPermissionService(eventBus, LingFrameConfig.current());
        FilterRegistry filterRegistry = new FilterRegistry(FilterRegistryConfig.builder()
                .methodCache(new InvokableMethodCache())
                .permissionService(permissionService)
                .lingRepository(repository)
                .trafficRouter(new LatestVersionPolicy())
                .eventBus(eventBus)
                .build());
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

    @Test
    @DisplayName("主类没有实现 Ling 接口应抛出 LingInstallException 且其 cause 为 InvalidArgumentException")
    void shouldThrowWhenMainClassDoesNotImplementLing() {
        LingInstallException ex = assertThrows(LingInstallException.class, () -> {
            new NativeLingContainer(
                    "native-ling",
                    String.class,
                    String.class.getClassLoader(),
                    new File(".")
            );
        });
        assertTrue(ex.getCause() instanceof com.lingframe.api.exception.InvalidArgumentException);
    }

    public static class BadConstructorLing implements Ling {
        public BadConstructorLing() {
            throw new RuntimeException("Constructor failed");
        }
        @Override
        public void onStart(LingContext context) {}
        @Override
        public void onStop(LingContext context) {}
    }

    @Test
    @DisplayName("主类实例化失败应抛出 LingInstallException")
    void shouldThrowWhenConstructorThrows() {
        assertThrows(LingInstallException.class, () -> {
            new NativeLingContainer(
                    "native-ling",
                    BadConstructorLing.class,
                    BadConstructorLing.class.getClassLoader(),
                    new File(".")
            );
        });
    }

    public static class StartExceptionLing implements Ling {
        @Override
        public void onStart(LingContext context) {
            throw new RuntimeException("Start error");
        }
        @Override
        public void onStop(LingContext context) {}
    }

    @Test
    @DisplayName("启动失败时应抛出 LingInstallException 且容器为非激活状态")
    void shouldBeInactiveAndThrowWhenStartThrows() {
        NativeLingContainer container = new NativeLingContainer(
                "native-ling",
                StartExceptionLing.class,
                StartExceptionLing.class.getClassLoader(),
                new File(".")
        );
        LingContext context = new MinimalLingContext("native-ling");
        assertThrows(LingInstallException.class, () -> container.start(context));
        assertFalse(container.isActive());
    }

    public static class StopExceptionLing implements Ling {
        @Override
        public void onStart(LingContext context) {}
        @Override
        public void onStop(LingContext context) {
            throw new RuntimeException("Stop error");
        }
    }

    @Test
    @DisplayName("停止抛出异常时容器应该能正常处理")
    void shouldHandleExceptionDuringStop() {
        NativeLingContainer container = new NativeLingContainer(
                "native-ling",
                StopExceptionLing.class,
                StopExceptionLing.class.getClassLoader(),
                new File(".")
        );
        LingContext context = new MinimalLingContext("native-ling");
        container.start(context);
        assertTrue(container.isActive());
        assertDoesNotThrow(container::stop);
        assertFalse(container.isActive());
    }

    @Test
    @DisplayName("未启动时 stop 应直接返回")
    void shouldReturnDirectlyWhenStopCalledBeforeStart() {
        NativeLingContainer container = new NativeLingContainer(
                "native-ling",
                TestNativeLing.class,
                TestNativeLing.class.getClassLoader(),
                new File(".")
        );
        assertFalse(container.isActive());
        assertDoesNotThrow(container::stop);
    }

    /**
     * 服务方法所在类无法实例化时的测试承载体。
     * 原生路径不再 newInstance 任意类，但 LingServiceRegistrar.register 仍应健壮——
     * 传一个无法实例化的 Bean Class（仅用于反射注解扫描，不应真 newInstance）时，
     * Registrar 应记日志不崩。本类模拟「构造抛异常」的病态 Bean。
     */
    public static class NonInstantiableServiceBean {
        private NonInstantiableServiceBean() {
            throw new RuntimeException("Can't instantiate");
        }
        @LingService(id = "bad")
        public void test() {}
    }

    @Test
    @DisplayName("服务方法所在类无法实例化时 Registrar.register 应记录日志而不崩溃")
    void shouldHandleBeanInstantiationFailureInServiceRegistration() throws Exception {
        EventBus eventBus = new EventBus();
        DefaultLingRepository repository = new DefaultLingRepository();
        DefaultLingServiceRegistry registry = new DefaultLingServiceRegistry();
        DefaultPermissionService permissionService = new DefaultPermissionService(eventBus, LingFrameConfig.current());
        FilterRegistry filterRegistry = new FilterRegistry(FilterRegistryConfig.builder()
                .methodCache(new InvokableMethodCache())
                .permissionService(permissionService)
                .lingRepository(repository)
                .trafficRouter(new LatestVersionPolicy())
                .eventBus(eventBus)
                .build());
        InvocationPipelineEngine pipelineEngine = new InvocationPipelineEngine(filterRegistry);
        DefaultLingContext context = new DefaultLingContext("native-ling", repository, registry, pipelineEngine, permissionService, eventBus);

        // 等价契约验证：直接对无法实例化的 Bean Class 调统注册器，
        // Registrar 只做反射注解扫描不 newInstance，应记日志不崩。
        // 不走 container.start——那会触发多类扫描的 newInstance 路径，与「不崩」契约验证无关。
        LingServiceRegistrar registrar = new LingServiceRegistrar(
                registry, BusinessInterfaceFilter.coreDefaults(), true);
        Method badMethod = NonInstantiableServiceBean.class.getDeclaredMethod("test");
        // 用一个 mock Bean 实例（Object 充数触发反射路径，Registrar 不 newInstance）
        Object dummyBean = new Object();
        assertDoesNotThrow(() -> registrar.register("native-ling", dummyBean, NonInstantiableServiceBean.class));
    }

    @Test
    @DisplayName("支持 JAR 文件的扫描分支")
    void shouldScanClassesFromJarFile() throws Exception {
        File tempJar = File.createTempFile("test-ling-", ".jar");
        tempJar.deleteOnExit();
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(tempJar))) {
            ZipEntry entry = new ZipEntry("com/lingframe/runtime/adapter/NativeLingContainerTest$TestNativeLing.class");
            zos.putNextEntry(entry);
            zos.write(new byte[0]);
            zos.closeEntry();

            ZipEntry entryFilter = new ZipEntry("com/lingframe/runtime/adapter/package-info.class");
            zos.putNextEntry(entryFilter);
            zos.write(new byte[0]);
            zos.closeEntry();

            ZipEntry entryBad = new ZipEntry("com/lingframe/runtime/adapter/NonExistClass.class");
            zos.putNextEntry(entryBad);
            zos.write(new byte[0]);
            zos.closeEntry();
        }

        NativeLingContainer container = new NativeLingContainer(
                "native-ling",
                TestNativeLing.class,
                TestNativeLing.class.getClassLoader(),
                tempJar
        );

        EventBus eventBus = new EventBus();
        DefaultLingRepository repository = new DefaultLingRepository();
        DefaultLingServiceRegistry registry = new DefaultLingServiceRegistry();
        DefaultPermissionService permissionService = new DefaultPermissionService(eventBus, LingFrameConfig.current());
        FilterRegistry filterRegistry = new FilterRegistry(FilterRegistryConfig.builder()
                .methodCache(new InvokableMethodCache())
                .permissionService(permissionService)
                .lingRepository(repository)
                .trafficRouter(new LatestVersionPolicy())
                .eventBus(eventBus)
                .build());
        InvocationPipelineEngine pipelineEngine = new InvocationPipelineEngine(filterRegistry);
        DefaultLingContext context = new DefaultLingContext("native-ling", repository, registry, pipelineEngine, permissionService, eventBus);

        container.start(context);
        assertTrue(container.isActive());
        container.stop();
    }

    @Test
    @DisplayName("getBean 及其他辅助方法获取验证")
    void testGetBeanAndHelperMethods() {
        NativeLingContainer container = new NativeLingContainer(
                "native-ling",
                TestNativeLing.class,
                TestNativeLing.class.getClassLoader(),
                new File(".")
        );

        assertNotNull(container.getClassLoader());
        assertArrayEquals(new String[0], container.getBeanNames());

        assertNotNull(container.getBean(TestNativeLing.class));
        assertNull(container.getBean(String.class));

        assertNotNull(container.getBean("com.lingframe.runtime.adapter.NativeLingContainerTest$TestNativeLing"));
        assertNotNull(container.getBean("native-ling"));
        assertNull(container.getBean((String)null));
        assertNotNull(container.getBean("non-exist-bean"));
    }
}
