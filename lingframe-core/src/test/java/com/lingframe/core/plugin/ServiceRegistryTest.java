package com.lingframe.core.plugin;

import com.lingframe.api.exception.InvalidArgumentException;
import com.lingframe.core.exception.ServiceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ServiceRegistry 单元测试")
public class ServiceRegistryTest {

    private static final String PLUGIN_ID = "test-plugin";

    private ServiceRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new ServiceRegistry(PLUGIN_ID);
    }

    // ==================== 测试服务类 ====================

    public static class TestService {
        public String hello(String name) {
            return "Hello, " + name;
        }

        public int add(int a, int b) {
            return a + b;
        }

        public void doSomething() {
            // void method
        }
    }

    public interface GreetingService {
        String greet(String name);
    }

    // ==================== 初始状态测试 ====================

    @Nested
    @DisplayName("初始状态")
    class InitialStateTests {

        @Test
        @DisplayName("新注册表应该为空")
        void newRegistryShouldBeEmpty() {
            assertEquals(0, registry.getServiceCount());
            assertEquals(0, registry.getProxyCacheSize());
            assertTrue(registry.getAllServiceIds().isEmpty());
        }

        @Test
        @DisplayName("初始统计信息应正确")
        void initialStatsShouldBeCorrect() {
            ServiceRegistry.RegistryStats stats = registry.getStats();

            assertEquals(0, stats.serviceCount());
            assertEquals(0, stats.proxyCacheSize());
        }
    }

    // ==================== 服务注册测试 ====================

    @Nested
    @DisplayName("服务注册")
    class RegistrationTests {

        @Test
        @DisplayName("注册服务应成功")
        void registerServiceShouldSucceed() throws Exception {
            TestService bean = new TestService();
            Method method = TestService.class.getMethod("hello", String.class);

            boolean isNew = registry.registerService("test:hello", bean, method);

            assertTrue(isNew);
            assertEquals(1, registry.getServiceCount());
            assertTrue(registry.hasService("test:hello"));
        }

        @Test
        @DisplayName("重复注册应返回 false 并覆盖")
        void duplicateRegistrationShouldOverwrite() throws Exception {
            TestService bean1 = new TestService();
            TestService bean2 = new TestService();
            Method method = TestService.class.getMethod("hello", String.class);

            assertTrue(registry.registerService("test:hello", bean1, method));
            assertFalse(registry.registerService("test:hello", bean2, method));

            assertEquals(1, registry.getServiceCount());
            // 应该是 bean2
            assertEquals(bean2, registry.getService("test:hello").bean());
        }

        @Test
        @DisplayName("注册多个服务应成功")
        void registerMultipleServicesShouldSucceed() throws Exception {
            TestService bean = new TestService();

            registry.registerService("test:hello", bean,
                    TestService.class.getMethod("hello", String.class));
            registry.registerService("test:add", bean,
                    TestService.class.getMethod("add", int.class, int.class));
            registry.registerService("test:doSomething", bean,
                    TestService.class.getMethod("doSomething"));

            assertEquals(3, registry.getServiceCount());

            Set<String> ids = registry.getAllServiceIds();
            assertTrue(ids.contains("test:hello"));
            assertTrue(ids.contains("test:add"));
            assertTrue(ids.contains("test:doSomething"));
        }

        @Test
        @DisplayName("批量注册应成功")
        void batchRegistrationShouldSucceed() throws Exception {
            TestService bean = new TestService();

            Map<String, ServiceRegistry.ServiceDefinition> services =
                    new HashMap<String, ServiceRegistry.ServiceDefinition>() {{
                        put("test:hello", new ServiceRegistry.ServiceDefinition(
                                bean, TestService.class.getMethod("hello", String.class)));
                        put("test:add", new ServiceRegistry.ServiceDefinition(
                                bean, TestService.class.getMethod("add", int.class, int.class)));
                    }};

            int count = registry.registerServices(services);

            assertEquals(2, count);
            assertEquals(2, registry.getServiceCount());
        }

        @Test
        @DisplayName("注册 null FQSID 应抛出异常")
        void registerNullFqsidShouldThrow() throws Exception {
            TestService bean = new TestService();
            Method method = TestService.class.getMethod("hello", String.class);

            assertThrows(InvalidArgumentException.class, () ->
                    registry.registerService(null, bean, method));
        }

        @Test
        @DisplayName("注册空白 FQSID 应抛出异常")
        void registerBlankFqsidShouldThrow() throws Exception {
            TestService bean = new TestService();
            Method method = TestService.class.getMethod("hello", String.class);

            assertThrows(InvalidArgumentException.class, () ->
                    registry.registerService("  ", bean, method));
        }

        @Test
        @DisplayName("注册 null bean 应抛出异常")
        void registerNullBeanShouldThrow() throws Exception {
            Method method = TestService.class.getMethod("hello", String.class);

            assertThrows(InvalidArgumentException.class, () ->
                    registry.registerService("test:hello", null, method));
        }

        @Test
        @DisplayName("注册 null method 应抛出异常")
        void registerNullMethodShouldThrow() {
            TestService bean = new TestService();

            assertThrows(InvalidArgumentException.class, () ->
                    registry.registerService("test:hello", bean, null));
        }
    }

    // ==================== 服务查询测试 ====================

    @Nested
    @DisplayName("服务查询")
    class QueryTests {

        @Test
        @DisplayName("getService 应返回注册的服务")
        void getServiceShouldReturnRegistered() throws Exception {
            TestService bean = new TestService();
            Method method = TestService.class.getMethod("hello", String.class);
            registry.registerService("test:hello", bean, method);

            ServiceRegistry.InvokableService service = registry.getService("test:hello");

            assertNotNull(service);
            assertEquals(bean, service.bean());
            assertEquals(method, service.method());
            assertNotNull(service.methodHandle());
        }

        @Test
        @DisplayName("getService 对不存在的服务应返回 null")
        void getServiceShouldReturnNullForNonExistent() {
            assertNull(registry.getService("non:existent"));
        }

        @Test
        @DisplayName("getServiceRequired 应返回服务")
        void getServiceRequiredShouldReturnService() throws Exception {
            TestService bean = new TestService();
            Method method = TestService.class.getMethod("hello", String.class);
            registry.registerService("test:hello", bean, method);

            ServiceRegistry.InvokableService service = registry.getServiceRequired("test:hello");

            assertNotNull(service);
        }

        @Test
        @DisplayName("getServiceRequired 对不存在的服务应抛出异常")
        void getServiceRequiredShouldThrowForNonExistent() {
            assertThrows(ServiceNotFoundException.class, () ->
                    registry.getServiceRequired("non:existent"));
        }

        @Test
        @DisplayName("hasService 应正确判断")
        void hasServiceShouldWork() throws Exception {
            TestService bean = new TestService();
            Method method = TestService.class.getMethod("hello", String.class);
            registry.registerService("test:hello", bean, method);

            assertTrue(registry.hasService("test:hello"));
            assertFalse(registry.hasService("non:existent"));
        }
    }

    // ==================== 服务注销测试 ====================

    @Nested
    @DisplayName("服务注销")
    class UnregistrationTests {

        @Test
        @DisplayName("注销已注册的服务应成功")
        void unregisterExistingShouldSucceed() throws Exception {
            TestService bean = new TestService();
            Method method = TestService.class.getMethod("hello", String.class);
            registry.registerService("test:hello", bean, method);

            boolean removed = registry.unregisterService("test:hello");

            assertTrue(removed);
            assertFalse(registry.hasService("test:hello"));
            assertEquals(0, registry.getServiceCount());
        }

        @Test
        @DisplayName("注销不存在的服务应返回 false")
        void unregisterNonExistentShouldReturnFalse() {
            boolean removed = registry.unregisterService("non:existent");

            assertFalse(removed);
        }
    }

    // ==================== InvokableService 测试 ====================

    @Nested
    @DisplayName("InvokableService 调用")
    class InvokableServiceTests {

        @Test
        @DisplayName("invokeFast 应正确执行")
        void invokeFastShouldWork() throws Throwable {
            TestService bean = new TestService();
            Method method = TestService.class.getMethod("hello", String.class);
            registry.registerService("test:hello", bean, method);

            ServiceRegistry.InvokableService service = registry.getService("test:hello");
            Object result = service.invokeFast("World");

            assertEquals("Hello, World", result);
        }

        @Test
        @DisplayName("invokeReflect 应正确执行")
        void invokeReflectShouldWork() throws Exception {
            TestService bean = new TestService();
            Method method = TestService.class.getMethod("add", int.class, int.class);
            registry.registerService("test:add", bean, method);

            ServiceRegistry.InvokableService service = registry.getService("test:add");
            Object result = service.invokeReflect(3, 5);

            assertEquals(8, result);
        }

        @Test
        @DisplayName("getSignature 应返回正确签名")
        void getSignatureShouldWork() throws Exception {
            TestService bean = new TestService();
            Method method = TestService.class.getMethod("hello", String.class);
            registry.registerService("test:hello", bean, method);

            ServiceRegistry.InvokableService service = registry.getService("test:hello");
            String signature = service.getSignature();

            assertEquals("TestService.hello", signature);
        }
    }

    // ==================== 代理缓存测试 ====================

    @Nested
    @DisplayName("代理缓存")
    class ProxyCacheTests {

        @Test
        @DisplayName("getOrCreateProxy 应创建并缓存代理")
        void getOrCreateProxyShouldWork() {
            Object proxy = registry.getOrCreateProxy(GreetingService.class,
                    k -> new Object());

            assertNotNull(proxy);
            assertEquals(1, registry.getProxyCacheSize());
            assertTrue(registry.hasProxy(GreetingService.class));
        }

        @Test
        @DisplayName("getOrCreateProxy 应复用缓存")
        void getOrCreateProxyShouldReuse() {
            AtomicInteger createCount = new AtomicInteger(0);

            Object proxy1 = registry.getOrCreateProxy(GreetingService.class,
                    k -> {
                        createCount.incrementAndGet();
                        return new Object();
                    });

            Object proxy2 = registry.getOrCreateProxy(GreetingService.class,
                    k -> {
                        createCount.incrementAndGet();
                        return new Object();
                    });

            assertSame(proxy1, proxy2);
            assertEquals(1, createCount.get());
        }

        @Test
        @DisplayName("getCachedProxy 应返回缓存的代理")
        void getCachedProxyShouldWork() {
            Object created = registry.getOrCreateProxy(GreetingService.class,
                    k -> new Object());
            Object cached = registry.getCachedProxy(GreetingService.class);

            assertSame(created, cached);
        }

        @Test
        @DisplayName("getCachedProxy 无缓存时应返回 null")
        void getCachedProxyShouldReturnNullWhenNotCached() {
            assertNull(registry.getCachedProxy(GreetingService.class));
        }

        @Test
        @DisplayName("removeProxy 应移除缓存")
        void removeProxyShouldWork() {
            registry.getOrCreateProxy(GreetingService.class, k -> new Object());

            registry.removeProxy(GreetingService.class);

            assertFalse(registry.hasProxy(GreetingService.class));
            assertEquals(0, registry.getProxyCacheSize());
        }
    }

    // ==================== 清理测试 ====================

    @Nested
    @DisplayName("清理功能")
    class ClearTests {

        @Test
        @DisplayName("clear 应清空所有缓存")
        void clearShouldClearAll() throws Exception {
            TestService bean = new TestService();
            registry.registerService("test:hello", bean,
                    TestService.class.getMethod("hello", String.class));
            registry.getOrCreateProxy(GreetingService.class, k -> new Object());

            registry.clear();

            assertEquals(0, registry.getServiceCount());
            assertEquals(0, registry.getProxyCacheSize());
        }

        @Test
        @DisplayName("clearProxyCache 只应清空代理缓存")
        void clearProxyCacheShouldOnlyClearProxies() throws Exception {
            TestService bean = new TestService();
            registry.registerService("test:hello", bean,
                    TestService.class.getMethod("hello", String.class));
            registry.getOrCreateProxy(GreetingService.class, k -> new Object());

            registry.clearProxyCache();

            assertEquals(1, registry.getServiceCount());
            assertEquals(0, registry.getProxyCacheSize());
        }
    }

    // ==================== 并发测试 ====================

    @Nested
    @DisplayName("并发安全")
    class ConcurrencyTests {

        @Test
        @DisplayName("并发注册服务应安全")
        void concurrentRegistrationShouldBeSafe() throws Exception {
            int threadCount = 10;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(threadCount);

            TestService bean = new TestService();
            Method method = TestService.class.getMethod("hello", String.class);

            for (int i = 0; i < threadCount; i++) {
                final int index = i;
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        registry.registerService("test:service" + index, bean, method);
                    } catch (Exception e) {
                        // ignore
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            boolean completed = doneLatch.await(10, TimeUnit.SECONDS);
            executor.shutdown();

            assertTrue(completed);
            assertEquals(threadCount, registry.getServiceCount());
        }

        @Test
        @DisplayName("并发获取代理应安全")
        void concurrentGetProxyShouldBeSafe() throws InterruptedException {
            int threadCount = 20;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(threadCount);
            AtomicInteger createCount = new AtomicInteger(0);

            for (int i = 0; i < threadCount; i++) {
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        registry.getOrCreateProxy(GreetingService.class, k -> {
                            createCount.incrementAndGet();
                            return new Object();
                        });
                    } catch (Exception e) {
                        // ignore
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            boolean completed = doneLatch.await(10, TimeUnit.SECONDS);
            executor.shutdown();

            assertTrue(completed);
            // 只应该创建一次
            assertEquals(1, createCount.get());
            assertEquals(1, registry.getProxyCacheSize());
        }
    }

    // ==================== 统计信息测试 ====================

    @Nested
    @DisplayName("统计信息")
    class StatsTests {

        @Test
        @DisplayName("getStats 应返回正确统计")
        void getStatsShouldBeCorrect() throws Exception {
            TestService bean = new TestService();
            registry.registerService("test:hello", bean,
                    TestService.class.getMethod("hello", String.class));
            registry.registerService("test:add", bean,
                    TestService.class.getMethod("add", int.class, int.class));
            registry.getOrCreateProxy(GreetingService.class, k -> new Object());

            ServiceRegistry.RegistryStats stats = registry.getStats();

            assertEquals(2, stats.serviceCount());
            assertEquals(1, stats.proxyCacheSize());
        }

        @Test
        @DisplayName("RegistryStats toString 应包含关键信息")
        void statsToStringShouldWork() throws Exception {
            TestService bean = new TestService();
            registry.registerService("test:hello", bean,
                    TestService.class.getMethod("hello", String.class));

            String str = registry.getStats().toString();

            assertTrue(str.contains("services=1"));
            assertTrue(str.contains("proxies=0"));
        }
    }
}