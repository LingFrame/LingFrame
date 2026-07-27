package com.lingframe.dashboard.converter;

import com.lingframe.api.config.GovernancePolicy;
import com.lingframe.api.config.LingDefinition;
import com.lingframe.api.security.AccessType;
import com.lingframe.api.security.Capabilities;
import com.lingframe.api.security.PermissionInfo;
import com.lingframe.api.security.PermissionService;
import com.lingframe.core.event.EventBus;
import com.lingframe.core.fsm.RuntimeStatus;
import com.lingframe.core.ling.InstancePool;
import com.lingframe.core.ling.LingInstance;
import com.lingframe.core.ling.LingRuntime;
// CanaryRouter 已删除，路由层去身份化
import com.lingframe.core.spi.LingContainer;
import com.lingframe.dashboard.dto.LingInfoDTO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("LingInfoConverter 测试")
class LingInfoConverterTest {

    @Test
    @DisplayName("应提取细粒度 SQL 与 Redis capability 并保留本地缓存命名空间说明")
    void shouldExposeFineGrainedCapabilities() {
        LingRuntime runtime = mock(LingRuntime.class);
        InstancePool instancePool = mock(InstancePool.class);
        // CanaryRouter 已删除
        PermissionService permissionService = mock(PermissionService.class);

        when(runtime.getLingId()).thenReturn("ling-a");
        when(runtime.currentStatus()).thenReturn(com.lingframe.core.fsm.RuntimeStatus.ACTIVE);
        when(runtime.getInstancePool()).thenReturn(instancePool);
        when(runtime.getInstalledAt()).thenReturn(123L);
        when(instancePool.getActiveInstances()).thenReturn(Collections.<LingInstance>emptyList());
        when(instancePool.getAllInstances()).thenReturn(Collections.<LingInstance>emptyList());
        // canaryRouter 已删除

        when(permissionService.getPermission("ling-a", Capabilities.STORAGE_SQL))
                .thenReturn(PermissionInfo.permanent("ling-a", Capabilities.STORAGE_SQL, AccessType.WRITE, "test"));
        when(permissionService.getPermission("ling-a", Capabilities.CACHE_LOCAL))
                .thenReturn(PermissionInfo.permanent("ling-a", Capabilities.CACHE_LOCAL, AccessType.READ, "test"));

        GovernancePolicy policy = new GovernancePolicy();
        policy.setCapabilities(Arrays.asList(
                GovernancePolicy.CapabilityRule.builder()
                        .capability("storage:sql:table:users")
                        .accessType(AccessType.READ.name())
                        .build(),
                GovernancePolicy.CapabilityRule.builder()
                        .capability("cache:redis:key:user:*")
                        .accessType(AccessType.WRITE.name())
                        .build(),
                GovernancePolicy.CapabilityRule.builder()
                        .capability("custom:export")
                        .accessType(AccessType.EXECUTE.name())
                        .build(),
                GovernancePolicy.CapabilityRule.builder()
                        .capability("ipc:order-ling")
                        .accessType(AccessType.EXECUTE.name())
                        .build()));
        policy.getInvocation().setCpuBudgetMsPerMinute(900);
        policy.getInvocation().setMemoryBudgetMb(64);

        LingInfoDTO dto = new LingInfoConverter(null).toDTO(runtime, permissionService, policy);

        assertEquals(1, dto.getPermissions().getSqlCapabilities().size());
        assertEquals("storage:sql:table:users", dto.getPermissions().getSqlCapabilities().get(0));
        assertEquals(1, dto.getPermissions().getRedisCapabilities().size());
        assertEquals("cache:redis:key:user:*", dto.getPermissions().getRedisCapabilities().get(0));
        assertEquals(1, dto.getPermissions().getExtraCapabilities().size());
        assertEquals("custom:export", dto.getPermissions().getExtraCapabilities().get(0));
        assertTrue(dto.getPermissions().getIpcServices().contains("order-ling"));
        assertEquals("lingId + cacheName + rawKey", dto.getPermissions().getLocalCacheNamespaceStrategy());
        assertEquals(900, dto.getInvocationGovernance().getCpuBudgetMsPerMinute());
        assertEquals(64, dto.getInvocationGovernance().getMemoryBudgetMb());
    }

    @Test
    @DisplayName("reload 时序窗口：dyingQueue 中的旧实例不应出现在版本列表")
    void shouldFilterDestroyedInstanceDuringReload() {
        // 模拟 reload 场景：
        // - activePool 有新实例（READY）
        // - dyingQueue 有旧实例（STOPPING 状态，仍在 drain）
        // 期望：versionDetails 只包含 activePool 中的新实例
        LingRuntime runtime = mock(LingRuntime.class);
        InstancePool instancePool = mock(InstancePool.class);
        // CanaryRouter 已删除
        PermissionService permissionService = mock(PermissionService.class);
        EventBus eventBus = mock(EventBus.class);

        when(runtime.getLingId()).thenReturn("ling-a");
        when(runtime.currentStatus()).thenReturn(RuntimeStatus.ACTIVE);
        when(runtime.getInstancePool()).thenReturn(instancePool);
        when(runtime.getInstalledAt()).thenReturn(123L);
        // canaryRouter 已删除

        // 新实例：正常 READY 状态
        LingDefinition newDef = new LingDefinition();
        newDef.setId("ling-a");
        newDef.setVersion("1.0.0-reload-1");
        LingContainer newContainer = mock(LingContainer.class);
        when(newContainer.isActive()).thenReturn(true);
        LingInstance newInstance = new LingInstance(newContainer, newDef, eventBus);

        // 旧实例：在 dyingQueue 中，STOPPING 状态（drain 中）
        LingInstance oldInstance = mock(LingInstance.class);
        when(oldInstance.getDefinition()).thenReturn(new LingDefinition());
        when(oldInstance.getVersion()).thenReturn("1.0.0");
        when(oldInstance.currentStatus()).thenReturn(com.lingframe.core.fsm.InstanceStatus.STOPPING);

        // activeInstances 只含新实例，getAllInstances 含两者
        when(instancePool.getActiveInstances()).thenReturn(Collections.singletonList(newInstance));
        when(instancePool.getDefault()).thenReturn(newInstance);

        LingInfoDTO dto = new LingInfoConverter(null).toDTO(runtime, permissionService, null);

        // 只应返回 1 个版本（新实例），dyingQueue 中的旧实例不展示
        assertEquals(1, dto.getVersionDetails().size());
        assertEquals("1.0.0-reload-1", dto.getVersionDetails().get(0).getVersion());
    }

    // ==================== Canary 版本回退逻辑测试 ====================
    // 验证 versionDetails 中 isCanary/isDefault 标志正确填充，
    // 这是 JS 端 canaryVer = find(v => v.isCanary)?.version || find(v => !v.isDefault)?.version
    // 回退逻辑的数据前提。

    @Test
    @DisplayName("canary 标志：isCanary=true 的实例应正确标记且流量权重等于 canaryPercent")
    void shouldMarkCanaryInstanceAndSetWeight() {
        LingRuntime runtime = mock(LingRuntime.class);
        InstancePool pool = mock(InstancePool.class);
        // CanaryRouter 已删除
        PermissionService permSvc = mock(PermissionService.class);
        EventBus eventBus = mock(EventBus.class);

        when(runtime.getLingId()).thenReturn("ling-a");
        when(runtime.currentStatus()).thenReturn(RuntimeStatus.ACTIVE);
        when(runtime.getInstancePool()).thenReturn(pool);
        when(runtime.getInstalledAt()).thenReturn(123L);
        // router 已删除

        // 默认实例
        LingDefinition defaultDef = new LingDefinition();
        defaultDef.setId("ling-a");
        defaultDef.setVersion("1.0.0");
        LingContainer defaultContainer = mock(LingContainer.class);
        when(defaultContainer.isActive()).thenReturn(true);
        LingInstance defaultInstance = new LingInstance(defaultContainer, defaultDef, eventBus);

        // canary 实例（properties 中 canary=true）
        LingDefinition canaryDef = new LingDefinition();
        canaryDef.setId("ling-a");
        canaryDef.setVersion("2.0.0");
        Map<String, Object> canaryProps = new HashMap<>();
        canaryProps.put("canary", true);
        canaryDef.setProperties(canaryProps);
        LingContainer canaryContainer = mock(LingContainer.class);
        when(canaryContainer.isActive()).thenReturn(true);
        LingInstance canaryInstance = new LingInstance(canaryContainer, canaryDef, eventBus);

        when(pool.getActiveInstances()).thenReturn(Arrays.asList(defaultInstance, canaryInstance));
        when(pool.getDefault()).thenReturn(defaultInstance);

        LingInfoDTO dto = new LingInfoConverter(null).toDTO(runtime, permSvc, null);

        assertEquals(2, dto.getVersionDetails().size());

        LingInfoDTO.VersionInfo canaryInfo = dto.getVersionDetails().stream()
                .filter(v -> v.getIsCanary()).findFirst().orElse(null);
        assertNotNull(canaryInfo, "应存在 isCanary=true 的版本");
        assertEquals("2.0.0", canaryInfo.getVersion());
        assertEquals(30, canaryInfo.getTrafficWeight());

        LingInfoDTO.VersionInfo defaultInfo = dto.getVersionDetails().stream()
                .filter(v -> v.getIsDefault()).findFirst().orElse(null);
        assertNotNull(defaultInfo, "应存在 isDefault=true 的版本");
        assertEquals("1.0.0", defaultInfo.getVersion());
        assertEquals(70, defaultInfo.getTrafficWeight());
    }

    @Test
    @DisplayName("canary 回退：无 isCanary=true 时，非默认版本应可被 JS 回退逻辑选中")
    void shouldAllowFallbackToNonDefaultWhenNoCanaryFlag() {
        LingRuntime runtime = mock(LingRuntime.class);
        InstancePool pool = mock(InstancePool.class);
        // CanaryRouter 已删除
        PermissionService permSvc = mock(PermissionService.class);
        EventBus eventBus = mock(EventBus.class);

        when(runtime.getLingId()).thenReturn("ling-a");
        when(runtime.currentStatus()).thenReturn(RuntimeStatus.ACTIVE);
        when(runtime.getInstancePool()).thenReturn(pool);
        when(runtime.getInstalledAt()).thenReturn(123L);
        // router 已删除

        // 默认实例
        LingDefinition defaultDef = new LingDefinition();
        defaultDef.setId("ling-a");
        defaultDef.setVersion("1.0.0");
        LingContainer defaultContainer = mock(LingContainer.class);
        when(defaultContainer.isActive()).thenReturn(true);
        LingInstance defaultInstance = new LingInstance(defaultContainer, defaultDef, eventBus);

        // 第二个实例，无 canary 标志（模拟旧版灵元未声明 canary 属性）
        LingDefinition otherDef = new LingDefinition();
        otherDef.setId("ling-a");
        otherDef.setVersion("1.1.0");
        LingContainer otherContainer = mock(LingContainer.class);
        when(otherContainer.isActive()).thenReturn(true);
        LingInstance otherInstance = new LingInstance(otherContainer, otherDef, eventBus);

        when(pool.getActiveInstances()).thenReturn(Arrays.asList(defaultInstance, otherInstance));
        when(pool.getDefault()).thenReturn(defaultInstance);

        LingInfoDTO dto = new LingInfoConverter(null).toDTO(runtime, permSvc, null);

        assertEquals(2, dto.getVersionDetails().size());

        // 验证 JS 回退逻辑的前提：存在一个 isCanary=false 且 isDefault=false 的版本
        LingInfoDTO.VersionInfo nonDefaultNonCanary = dto.getVersionDetails().stream()
                .filter(v -> !v.getIsCanary() && !v.getIsDefault())
                .findFirst().orElse(null);
        assertNotNull(nonDefaultNonCanary, "JS 回退逻辑需要 !isCanary && !isDefault 的版本存在");
        assertEquals("1.1.0", nonDefaultNonCanary.getVersion());
    }

    @Test
    @DisplayName("canary 标志：canary 属性为数字 1 时应识别为 canary")
    void shouldRecognizeNumericCanaryFlag() {
        LingRuntime runtime = mock(LingRuntime.class);
        InstancePool pool = mock(InstancePool.class);
        // CanaryRouter 已删除
        PermissionService permSvc = mock(PermissionService.class);
        EventBus eventBus = mock(EventBus.class);

        when(runtime.getLingId()).thenReturn("ling-a");
        when(runtime.currentStatus()).thenReturn(RuntimeStatus.ACTIVE);
        when(runtime.getInstancePool()).thenReturn(pool);
        when(runtime.getInstalledAt()).thenReturn(123L);
        // router 已删除

        LingDefinition def = new LingDefinition();
        def.setId("ling-a");
        def.setVersion("2.0.0");
        Map<String, Object> props = new HashMap<>();
        props.put("canary", 1); // 数字 1 而非 boolean true
        def.setProperties(props);
        LingContainer container = mock(LingContainer.class);
        when(container.isActive()).thenReturn(true);
        LingInstance instance = new LingInstance(container, def, eventBus);

        when(pool.getActiveInstances()).thenReturn(Collections.singletonList(instance));
        when(pool.getDefault()).thenReturn(instance);

        LingInfoDTO dto = new LingInfoConverter(null).toDTO(runtime, permSvc, null);

        assertEquals(1, dto.getVersionDetails().size());
        assertTrue(dto.getVersionDetails().get(0).getIsCanary(), "canary=1 应识别为 canary");
    }
}
