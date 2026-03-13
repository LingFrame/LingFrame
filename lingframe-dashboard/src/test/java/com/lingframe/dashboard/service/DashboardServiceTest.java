package com.lingframe.dashboard.service;

import com.lingframe.api.config.GovernancePolicy;
import com.lingframe.api.security.AccessType;
import com.lingframe.api.security.Capabilities;
import com.lingframe.api.security.PermissionService;
import com.lingframe.core.config.LingFrameConfig;
import com.lingframe.core.governance.LocalGovernanceRegistry;
import com.lingframe.core.ling.LingLifecycleEngine;
import com.lingframe.core.ling.LingRepository;
import com.lingframe.core.router.CanaryRouter;
import com.lingframe.dashboard.converter.LingInfoConverter;
import com.lingframe.dashboard.dto.ResourcePermissionDTO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.eq;

@ExtendWith(MockitoExtension.class)
class DashboardServiceTest {

    @Mock
    LingFrameConfig lingFrameConfig;

    @Mock
    LingLifecycleEngine lifecycleEngine;
    @Mock
    LingRepository lingRepository;
    @Mock
    LocalGovernanceRegistry governanceRegistry;
    @Mock
    CanaryRouter canaryRouter;
    @Mock
    LingInfoConverter lingInfoConverter;
    @Mock
    PermissionService permissionService;

    @Captor
    ArgumentCaptor<GovernancePolicy> policyCaptor;

    @Test
    void updateGovernancePolicyRefreshesPermissionsFromPolicy() {
        DashboardService service = new DashboardService(lingFrameConfig, lifecycleEngine, lingRepository,
                governanceRegistry, canaryRouter, lingInfoConverter, permissionService);

        GovernancePolicy policy = new GovernancePolicy();
        policy.setCapabilities(Arrays.asList(
                GovernancePolicy.CapabilityRule.builder()
                        .capability(Capabilities.STORAGE_SQL)
                        .accessType(AccessType.WRITE.name())
                        .build(),
                GovernancePolicy.CapabilityRule.builder()
                        .capability(Capabilities.CACHE_LOCAL)
                        .accessType(AccessType.READ.name())
                        .build()));

        service.updateGovernancePolicy("ling1", policy);

        verify(governanceRegistry).updatePatch("ling1", policy);
        verify(permissionService).removeLing("ling1");
        verify(permissionService).grant("ling1", Capabilities.STORAGE_SQL, AccessType.WRITE);
        verify(permissionService).grant("ling1", Capabilities.CACHE_LOCAL, AccessType.READ);
    }

    @Test
    void updatePermissionsPersistsPolicyAndSyncsRuntimePermissions() {
        DashboardService service = new DashboardService(lingFrameConfig, lifecycleEngine, lingRepository,
                governanceRegistry, canaryRouter, lingInfoConverter, permissionService);

        when(governanceRegistry.getPatch("ling1")).thenReturn(null);

        ResourcePermissionDTO dto = new ResourcePermissionDTO();
        dto.setDbRead(true);
        dto.setDbWrite(false);
        dto.setCacheRead(true);
        dto.setCacheWrite(true);
        dto.setIpcServices(Arrays.asList("lingA", "lingB"));

        service.updatePermissions("ling1", dto);

        verify(governanceRegistry).updatePatch(eq("ling1"), policyCaptor.capture());
        GovernancePolicy saved = policyCaptor.getValue();
        assertEquals(5, saved.getCapabilities().size());

        verify(permissionService).removeLing("ling1");
        verify(permissionService).grant("ling1", Capabilities.STORAGE_SQL, AccessType.READ);
        verify(permissionService).grant("ling1", Capabilities.CACHE_LOCAL, AccessType.WRITE);
        verify(permissionService).grant("ling1", Capabilities.Ling_ENABLE, AccessType.EXECUTE);
        verify(permissionService).grant("ling1", "ipc:lingA", AccessType.EXECUTE);
        verify(permissionService).grant("ling1", "ipc:lingB", AccessType.EXECUTE);
    }
}
