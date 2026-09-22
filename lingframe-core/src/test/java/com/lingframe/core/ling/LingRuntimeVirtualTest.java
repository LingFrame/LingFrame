package com.lingframe.core.ling;

import com.lingframe.core.event.EventBus;
import com.lingframe.core.fsm.RuntimeCoordinator;
import com.lingframe.core.fsm.RuntimeStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 虚拟灵元管理器（VirtualLingManager）生产级功能与生命周期测试。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("VirtualLingManager 虚拟灵元管理服务测试")
class LingRuntimeVirtualTest {

    private static final String VIRTUAL_LING_ID = "seatunnel-virtual";

    @Mock
    private EventBus eventBus;

    private LingRepository lingRepository;
    private RuntimeCoordinator runtimeCoordinator;
    private VirtualLingManager virtualLingManager;

    @BeforeEach
    void setUp() {
        lingRepository = new DefaultLingRepository();
        runtimeCoordinator = new RuntimeCoordinator(eventBus);
        virtualLingManager = new VirtualLingManager(lingRepository, runtimeCoordinator, eventBus);
    }

    @Test
    @DisplayName("注册虚拟灵元应原子完成状态机初始化并推进至 ACTIVE 状态")
    void shouldRegisterAndActivateVirtualLing() {
        LingRuntime runtime = virtualLingManager.register(VIRTUAL_LING_ID);

        assertNotNull(runtime);
        assertEquals(VIRTUAL_LING_ID, runtime.getLingId());
        assertTrue(runtime.isVirtual());
        assertNull(runtime.getInstancePool());
        assertEquals(RuntimeStatus.ACTIVE, runtime.currentStatus());
        assertTrue(runtime.isAvailable());

        // 验证仓储与管理器查询一致
        assertTrue(virtualLingManager.hasRuntime(VIRTUAL_LING_ID));
        assertEquals(runtime, virtualLingManager.getRuntime(VIRTUAL_LING_ID));
        assertEquals(runtime, lingRepository.getRuntime(VIRTUAL_LING_ID));
    }

    @Test
    @DisplayName("注册自定义配置的虚拟灵元应正确保存配置参数")
    void shouldRegisterWithCustomConfig() {
        LingRuntimeConfig config = LingRuntimeConfig.builder()
                .rateLimitPerSecond(600)
                .circuitBreakerFailureRateThreshold(40)
                .circuitBreakerSlidingWindowSize(50)
                .defaultTimeoutMs(5000)
                .build();

        LingRuntime runtime = virtualLingManager.register(VIRTUAL_LING_ID, config);

        assertNotNull(runtime);
        assertEquals(600, runtime.getConfig().getRateLimitPerSecond());
        assertEquals(40, runtime.getConfig().getCircuitBreakerFailureRateThreshold());
        assertEquals(50, runtime.getConfig().getCircuitBreakerSlidingWindowSize());
        assertEquals(5000, runtime.getConfig().getDefaultTimeoutMs());
    }

    @Test
    @DisplayName("重复注册同一虚拟灵元应幂等更新配置并保持 ACTIVE 状态")
    void shouldUpdateConfigOnDuplicateRegistration() {
        virtualLingManager.register(VIRTUAL_LING_ID);
        assertEquals(0, virtualLingManager.getRuntime(VIRTUAL_LING_ID).getConfig().getRateLimitPerSecond());

        LingRuntimeConfig newConfig = LingRuntimeConfig.builder()
                .rateLimitPerSecond(1200)
                .build();
        LingRuntime updated = virtualLingManager.register(VIRTUAL_LING_ID, newConfig);

        assertEquals(1200, updated.getConfig().getRateLimitPerSecond());
        assertEquals(RuntimeStatus.ACTIVE, updated.currentStatus());
    }

    @Test
    @DisplayName("尝试注册同名物理灵元已存在的虚拟灵元应抛出异常")
    void shouldRejectWhenPhysicalLingAlreadyExists() {
        // 模拟物理灵元已存在
        InstanceCoordinator ic = new InstanceCoordinator(eventBus);
        runtimeCoordinator.register(VIRTUAL_LING_ID);
        LingRuntime physicalRuntime = new LingRuntime(VIRTUAL_LING_ID, LingRuntimeConfig.defaults(), eventBus, ic, runtimeCoordinator);
        lingRepository.register(physicalRuntime);

        assertThrows(IllegalStateException.class, () -> virtualLingManager.register(VIRTUAL_LING_ID));
    }

    @Test
    @DisplayName("优雅注销虚拟灵元应清理状态机并从仓储中移除")
    void shouldGracefullyUnregisterVirtualLing() {
        virtualLingManager.register(VIRTUAL_LING_ID);
        assertTrue(virtualLingManager.hasRuntime(VIRTUAL_LING_ID));

        virtualLingManager.unregister(VIRTUAL_LING_ID);

        assertFalse(virtualLingManager.hasRuntime(VIRTUAL_LING_ID));
        assertNull(lingRepository.getRuntime(VIRTUAL_LING_ID));
        assertNull(runtimeCoordinator.getStatus(VIRTUAL_LING_ID));
    }

    @Test
    @DisplayName("获取就绪实例列表应安全返回空集合而不抛出异常")
    void shouldSafelyReturnEmptyReadyInstances() {
        LingRuntime runtime = virtualLingManager.register(VIRTUAL_LING_ID);
        List<LingInstance> instances = runtime.getReadyInstances();

        assertNotNull(instances);
        assertTrue(instances.isEmpty());
    }
}
