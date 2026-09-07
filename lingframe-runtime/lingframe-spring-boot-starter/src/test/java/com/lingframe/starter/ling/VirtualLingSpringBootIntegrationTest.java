package com.lingframe.starter.ling;

import com.lingframe.core.event.EventBus;
import com.lingframe.core.fsm.RuntimeCoordinator;
import com.lingframe.core.fsm.RuntimeStatus;
import com.lingframe.core.ling.DefaultLingRepository;
import com.lingframe.core.ling.LingRepository;
import com.lingframe.core.ling.LingRuntime;
import com.lingframe.core.ling.VirtualLingManager;
import com.lingframe.starter.config.LingFrameProperties;
import com.lingframe.starter.configuration.LingFrameRuntimeBeansConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;

/**
 * 虚拟灵元在 Spring Boot 环境下的依赖注入、声明式注册与生命周期管理测试。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("VirtualLing Spring Boot 统一纳管与装配测试")
class VirtualLingSpringBootIntegrationTest {

    @Mock
    private EventBus eventBus;

    @Mock
    private ObjectProvider<VirtualLingRegistration> registrationsProvider;

    private LingRepository lingRepository;
    private RuntimeCoordinator runtimeCoordinator;
    private VirtualLingManager virtualLingManager;
    private LingFrameRuntimeBeansConfiguration configuration;

    @BeforeEach
    void setUp() {
        lingRepository = new DefaultLingRepository();
        runtimeCoordinator = new RuntimeCoordinator(eventBus);
        configuration = new LingFrameRuntimeBeansConfiguration();
        virtualLingManager = configuration.virtualLingManager(lingRepository, runtimeCoordinator, eventBus);
    }

    @Test
    @DisplayName("验证 VirtualLingManager Bean 可直接编程式注册虚拟灵元")
    void shouldRegisterProgrammaticallyViaVirtualLingManagerBean() {
        assertNotNull(virtualLingManager);
        LingRuntime runtime = virtualLingManager.register("programmatic-ling");

        assertNotNull(runtime);
        assertEquals(RuntimeStatus.ACTIVE, runtime.currentStatus());
        assertTrue(runtime.isVirtual());
        assertTrue(runtime.isAvailable());
        assertNull(runtime.getInstancePool());
    }

    @Test
    @DisplayName("验证基于 YAML 配置属性自动装配并激活虚拟灵元")
    void shouldBootstrapFromProperties() {
        LingFrameProperties properties = new LingFrameProperties();
        LingFrameProperties.VirtualLingConfig cfg = new LingFrameProperties.VirtualLingConfig();
        cfg.setRateLimitPerSecond(800);
        cfg.setCircuitBreakerFailureRateThreshold(35);
        properties.getVirtualLings().put("seatunnel-yaml", cfg);

        lenient().when(registrationsProvider.orderedStream()).thenAnswer(invocation -> Stream.empty());

        SmartInitializingSingleton runner = configuration.virtualLingBootstrapRunner(
                virtualLingManager, properties, registrationsProvider);

        // 模拟 Spring 容器完成单例实例化后触发
        runner.afterSingletonsInstantiated();

        LingRuntime runtime = virtualLingManager.getRuntime("seatunnel-yaml");
        assertNotNull(runtime);
        assertEquals(RuntimeStatus.ACTIVE, runtime.currentStatus());
        assertEquals(800, runtime.getConfig().getRateLimitPerSecond());
        assertEquals(35, runtime.getConfig().getCircuitBreakerFailureRateThreshold());
    }

    @Test
    @DisplayName("验证自动收集 @Bean 声明式 VirtualLingRegistration 并批量激活")
    void shouldBootstrapFromDeclarativeRegistrations() {
        LingFrameProperties properties = new LingFrameProperties();

        VirtualLingRegistration gatewayReg = VirtualLingRegistration.builder()
                .lingId("declarative-gateway")
                .rateLimitPerSecond(1500)
                .circuitBreakerFailureRateThreshold(25)
                .build();

        VirtualLingRegistration meshReg = VirtualLingRegistration.builder()
                .lingId("declarative-mesh")
                .rateLimitPerSecond(3000)
                .circuitBreakerFailureRateThreshold(15)
                .build();

        lenient().when(registrationsProvider.orderedStream()).thenAnswer(invocation -> Stream.of(gatewayReg, meshReg));

        SmartInitializingSingleton runner = configuration.virtualLingBootstrapRunner(
                virtualLingManager, properties, registrationsProvider);

        runner.afterSingletonsInstantiated();

        LingRuntime gatewayRuntime = virtualLingManager.getRuntime("declarative-gateway");
        assertNotNull(gatewayRuntime);
        assertEquals(RuntimeStatus.ACTIVE, gatewayRuntime.currentStatus());
        assertEquals(1500, gatewayRuntime.getConfig().getRateLimitPerSecond());
        assertEquals(25, gatewayRuntime.getConfig().getCircuitBreakerFailureRateThreshold());

        LingRuntime meshRuntime = virtualLingManager.getRuntime("declarative-mesh");
        assertNotNull(meshRuntime);
        assertEquals(RuntimeStatus.ACTIVE, meshRuntime.currentStatus());
        assertEquals(3000, meshRuntime.getConfig().getRateLimitPerSecond());
        assertEquals(15, meshRuntime.getConfig().getCircuitBreakerFailureRateThreshold());
    }

    @Test
    @DisplayName("验证 Spring 容器关闭时自动触发优雅注销")
    void shouldUnregisterOnContainerShutdown() throws Exception {
        LingFrameProperties properties = new LingFrameProperties();
        properties.getVirtualLings().put("shutdown-test", new LingFrameProperties.VirtualLingConfig());

        VirtualLingRegistration reg = VirtualLingRegistration.of("shutdown-reg");
        lenient().when(registrationsProvider.orderedStream()).thenAnswer(invocation -> Stream.of(reg));

        SmartInitializingSingleton runner = configuration.virtualLingBootstrapRunner(
                virtualLingManager, properties, registrationsProvider);
        runner.afterSingletonsInstantiated();

        assertTrue(virtualLingManager.hasRuntime("shutdown-test"));
        assertTrue(virtualLingManager.hasRuntime("shutdown-reg"));

        // 模拟容器关闭
        DisposableBean shutdownHook = configuration.virtualLingShutdownHook(
                virtualLingManager, properties, registrationsProvider);
        shutdownHook.destroy();

        assertFalse(virtualLingManager.hasRuntime("shutdown-test"));
        assertFalse(virtualLingManager.hasRuntime("shutdown-reg"));
        assertNull(lingRepository.getRuntime("shutdown-test"));
        assertNull(lingRepository.getRuntime("shutdown-reg"));
    }
}
