package com.lingframe.dashboard.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lingframe.core.ling.LingServiceRegistry;
import com.lingframe.core.routing.ProviderDescriptor;
import com.lingframe.core.routing.ProviderWeightRouter;
import com.lingframe.dashboard.dto.ContractRoutingDTO;
import com.lingframe.dashboard.dto.ProviderWeightDTO;
import com.lingframe.dashboard.storage.GovernanceStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * ContractRoutingService 单元测试。
 * 覆盖：多 provider 契约列表、权重查询、权重下发、一键回滚。
 * <p>
 * 去身份化后 ProviderDescriptor 不再携带 ProviderKind，
 * 灵核识别通过 LingCoreConstants.LINGCORE_LING_ID 常量比较 lingId 实现，
 * 仅 Dashboard 运维视图用，不参与路由决策。
 */
@DisplayName("ContractRoutingService 单元测试")
class ContractRoutingServiceTest {

    private LingServiceRegistry lingServiceRegistry;
    private ProviderWeightRouter providerWeightRouter;
    private ContractRoutingService service;

    @BeforeEach
    void setUp() {
        lingServiceRegistry = mock(LingServiceRegistry.class);
        // 用真实 ProviderWeightRouter 而非 mock，验证 setProviderWeight → getOverrideWeight 闭环
        providerWeightRouter = new ProviderWeightRouter();
        service = new ContractRoutingService(lingServiceRegistry, providerWeightRouter);
    }

    // ==================== 多 provider 契约列表 ====================

    @Nested
    @DisplayName("列出多 provider 契约")
    class ListMultiProviderContracts {

        @Test
        @DisplayName("只返回有 ≥2 个 provider 的契约")
        void onlyReturnsMultiProviderContracts() {
            when(lingServiceRegistry.getAllContractIds())
                    .thenReturn(new HashSet<>(Arrays.asList("svc-a", "svc-b", "svc-c")));
            when(lingServiceRegistry.getProvidersByContractId("svc-a"))
                    .thenReturn(Arrays.asList(
                            new ProviderDescriptor("svc-a", "lingcore-app", 100),
                            new ProviderDescriptor("svc-a", "user-ling", 0)));
            when(lingServiceRegistry.getProvidersByContractId("svc-b"))
                    .thenReturn(Collections.singletonList(
                            new ProviderDescriptor("svc-b", "lingcore-app", 100)));
            when(lingServiceRegistry.getProvidersByContractId("svc-c"))
                    .thenReturn(Arrays.asList(
                            new ProviderDescriptor("svc-c", "lingcore-app", 100),
                            new ProviderDescriptor("svc-c", "ling-a", 0),
                            new ProviderDescriptor("svc-c", "ling-b", 0)));

            List<String> result = service.listMultiProviderContracts();

            assertEquals(2, result.size());
            assertTrue(result.contains("svc-a"));
            assertTrue(result.contains("svc-c"));
            assertFalse(result.contains("svc-b"));
        }

        @Test
        @DisplayName("同一灵元两个版本注册同一契约应列入多 provider 列表")
        void sameLingTwoVersionsCountAsMultiProvider() {
            when(lingServiceRegistry.getAllContractIds()).thenReturn(Collections.singleton("svc-x"));
            when(lingServiceRegistry.getProvidersByContractId("svc-x"))
                    .thenReturn(Arrays.asList(
                            new ProviderDescriptor("svc-x", "user-ling", "1.0.0", 50),
                            new ProviderDescriptor("svc-x", "user-ling", "1.1.0", 50)));

            List<String> result = service.listMultiProviderContracts();

            assertTrue(result.contains("svc-x"),
                    "同灵元两版本 provider 应计为多 provider 契约（契约列表核心回归）");
        }

        @Test
        @DisplayName("无任何注册时返回空列表")
        void emptyWhenNoContracts() {
            when(lingServiceRegistry.getAllContractIds()).thenReturn(Collections.emptySet());

            List<String> result = service.listMultiProviderContracts();

            assertTrue(result.isEmpty());
        }
    }

    // ==================== 查询契约路由 ====================

    @Nested
    @DisplayName("查询契约路由策略")
    class GetContractRouting {

        @Test
        @DisplayName("无覆盖时生效权重取注册时初始 weight")
        void registeredDefaultsWhenNoOverride() {
            // 灵核注册时 weight=100，灵元注册时 weight=0——身份影响已沉淀为 weight 数值
            when(lingServiceRegistry.getProvidersByContractId("svc-a"))
                    .thenReturn(Arrays.asList(
                            new ProviderDescriptor("svc-a", "lingcore-app", 100),
                            new ProviderDescriptor("svc-a", "user-ling", 0)));

            ContractRoutingDTO dto = service.getContractRouting("svc-a");

            assertEquals("svc-a", dto.getContractId());
            assertTrue(dto.isMultiProvider());
            assertEquals(2, dto.getProviders().size());
            // 注册默认值：灵核 100，灵元 0
            assertEquals(100, dto.getCoreEffectiveWeight());
            assertEquals(0, dto.getLingEffectiveWeight());

            // 验证灵核 provider DTO
            ProviderWeightDTO coreDto = dto.getProviders().get(0);
            assertEquals("lingcore-app", coreDto.getLingId());
            assertTrue(coreDto.isCoreBaseline(), "lingcore-app 应被识别为灵核 baseline");
            assertEquals(100, coreDto.getRegisteredWeight());
            assertNull(coreDto.getOverrideWeight());
            assertEquals(100, coreDto.getEffectiveWeight());

            // 验证灵元 provider DTO
            ProviderWeightDTO lingDto = dto.getProviders().get(1);
            assertEquals("user-ling", lingDto.getLingId());
            assertFalse(lingDto.isCoreBaseline(), "user-ling 应被识别为灵元");
            assertEquals(0, lingDto.getRegisteredWeight());
            assertNull(lingDto.getOverrideWeight());
            assertEquals(0, lingDto.getEffectiveWeight());
        }

        @Test
        @DisplayName("有覆盖时生效权重取覆盖值")
        void overrideTakesPrecedence() {
            when(lingServiceRegistry.getProvidersByContractId("svc-a"))
                    .thenReturn(Arrays.asList(
                            new ProviderDescriptor("svc-a", "lingcore-app", 100),
                            new ProviderDescriptor("svc-a", "user-ling", 0)));
            // 预设覆盖：灵核 30，灵元 70
            providerWeightRouter.setProviderWeight("svc-a", "lingcore-app", 30);
            providerWeightRouter.setProviderWeight("svc-a", "user-ling", 70);

            ContractRoutingDTO dto = service.getContractRouting("svc-a");

            assertEquals(30, dto.getCoreEffectiveWeight());
            assertEquals(70, dto.getLingEffectiveWeight());

            // 验证每个 provider 的 DTO
            ProviderWeightDTO coreDto = dto.getProviders().get(0);
            assertEquals(100, coreDto.getRegisteredWeight());
            assertEquals(30, coreDto.getOverrideWeight());
            assertEquals(30, coreDto.getEffectiveWeight());

            ProviderWeightDTO lingDto = dto.getProviders().get(1);
            assertEquals(0, lingDto.getRegisteredWeight());
            assertEquals(70, lingDto.getOverrideWeight());
            assertEquals(70, lingDto.getEffectiveWeight());
        }

        @Test
        @DisplayName("同灵元多版本契约应返回两个不同版本的 provider")
        void sameLingMultiVersionReturnsBothProviders() {
            when(lingServiceRegistry.getProvidersByContractId("svc-x"))
                    .thenReturn(Arrays.asList(
                            new ProviderDescriptor("svc-x", "user-ling", "1.0.0", 30),
                            new ProviderDescriptor("svc-x", "user-ling", "1.1.0", 70)));

            ContractRoutingDTO dto = service.getContractRouting("svc-x");

            assertTrue(dto.isMultiProvider());
            assertEquals(2, dto.getProviders().size());
            assertEquals("1.0.0", dto.getProviders().get(0).getVersion());
            assertEquals("1.1.0", dto.getProviders().get(1).getVersion());
            assertEquals(100, dto.getLingEffectiveWeight());
        }

        @Test
        @DisplayName("契约未注册时返回空 providers 列表")
        void emptyWhenContractNotRegistered() {
            when(lingServiceRegistry.getProvidersByContractId("unknown"))
                    .thenReturn(Collections.emptyList());

            ContractRoutingDTO dto = service.getContractRouting("unknown");

            assertEquals("unknown", dto.getContractId());
            assertTrue(dto.getProviders().isEmpty());
            assertFalse(dto.isMultiProvider());
            assertEquals(0, dto.getCoreEffectiveWeight());
            assertEquals(0, dto.getLingEffectiveWeight());
        }
    }

    // ==================== 设置权重 ====================

    @Nested
    @DisplayName("设置 provider 权重")
    class SetProviderWeight {

        @Test
        @DisplayName("下发权重后 router 能读到")
        void weightIsReadableAfterSet() {
            service.setProviderWeight("svc-a", "user-ling", 50);

            Integer override = providerWeightRouter.getOverrideWeight("svc-a", "user-ling");
            assertEquals(50, override);
        }

        @Test
        @DisplayName("权重 clamp 到 [0, 100]")
        void weightClampedToRange() {
            service.setProviderWeight("svc-a", "user-ling", 150);
            assertEquals(100, providerWeightRouter.getOverrideWeight("svc-a", "user-ling"));

            service.setProviderWeight("svc-a", "user-ling", -10);
            assertEquals(0, providerWeightRouter.getOverrideWeight("svc-a", "user-ling"));
        }

        @Test
        @DisplayName("设置权重时如果配置了 GovernanceStorage 则自动持久化")
        void weightIsPersistedWhenStorageAvailable() {
            GovernanceStorage storage = mock(GovernanceStorage.class);
            service.setGovernanceStorage(storage);
            service.setObjectMapper(new ObjectMapper());

            service.setProviderWeight("svc-a", "user-ling", 50);

            verify(storage).saveRoutingWeightConfig(eq("svc-a"), contains("\"user-ling\":50"));
        }
    }

    // ==================== 一键回滚 ====================

    @Nested
    @DisplayName("一键回滚到灵核 100%")
    class RollbackToCore {

        @Test
        @DisplayName("回滚后灵核 baseline=100 灵元=0")
        void rollbackSetsCore100Ling0() {
            when(lingServiceRegistry.getProvidersByContractId("svc-a"))
                    .thenReturn(Arrays.asList(
                            new ProviderDescriptor("svc-a", "lingcore-app", 100),
                            new ProviderDescriptor("svc-a", "user-ling", 0),
                            new ProviderDescriptor("svc-a", "ling-b", 0)));

            // 预设非默认覆盖
            providerWeightRouter.setProviderWeight("svc-a", "lingcore-app", 30);
            providerWeightRouter.setProviderWeight("svc-a", "user-ling", 70);

            // 执行回滚
            service.rollbackToCore("svc-a");

            // 验证回滚后权重
            ContractRoutingDTO dto = service.getContractRouting("svc-a");
            assertEquals(100, dto.getCoreEffectiveWeight());
            assertEquals(0, dto.getLingEffectiveWeight());
        }

        @Test
        @DisplayName("无灵核 provider 时回滚仍将所有灵元设为 0")
        void rollbackWithNoCoreProvider() {
            when(lingServiceRegistry.getProvidersByContractId("svc-b"))
                    .thenReturn(Arrays.asList(
                            new ProviderDescriptor("svc-b", "ling-a", 50),
                            new ProviderDescriptor("svc-b", "ling-b", 50)));

            // 预设覆盖
            providerWeightRouter.setProviderWeight("svc-b", "ling-a", 80);
            providerWeightRouter.setProviderWeight("svc-b", "ling-b", 20);

            service.rollbackToCore("svc-b");

            ContractRoutingDTO dto = service.getContractRouting("svc-b");
            assertEquals(0, dto.getCoreEffectiveWeight());
            assertEquals(0, dto.getLingEffectiveWeight());
        }

        @Test
        @DisplayName("回滚到灵核时自动持久化覆盖后的权重")
        void rollbackIsPersistedWhenStorageAvailable() {
            GovernanceStorage storage = mock(GovernanceStorage.class);
            service.setGovernanceStorage(storage);
            service.setObjectMapper(new ObjectMapper());

            when(lingServiceRegistry.getProvidersByContractId("svc-a"))
                    .thenReturn(Arrays.asList(
                            new ProviderDescriptor("svc-a", "lingcore-app", 100),
                            new ProviderDescriptor("svc-a", "user-ling", 0)));

            service.rollbackToCore("svc-a");

            verify(storage).saveRoutingWeightConfig(eq("svc-a"), anyString());
        }
    }
}

