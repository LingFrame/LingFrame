package com.lingframe.starter.configuration;

import com.lingframe.api.config.GovernancePolicy;
import com.lingframe.api.security.AccessType;
import com.lingframe.api.security.Capabilities;
import com.lingframe.api.security.PermissionService;
import com.lingframe.core.governance.LocalGovernanceRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;

import java.util.Collections;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@DisplayName("LingFrameCoreConfiguration 测试")
class LingFrameCoreConfigurationTest {

    @Test
    @DisplayName("governancePermissionRestoreListener 应在启动时恢复持久化权限")
    void governancePermissionRestoreListenerShouldRestorePersistedPermissionsOnStartup() {
        LocalGovernanceRegistry registry = mock(LocalGovernanceRegistry.class);
        PermissionService permissionService = mock(PermissionService.class);
        GovernancePolicy policy = GovernancePolicy.builder()
                .capabilities(Collections.singletonList(
                        GovernancePolicy.CapabilityRule.builder()
                                .capability(Capabilities.STORAGE_SQL)
                                .accessType("WRITE")
                                .build()))
                .build();

        when(registry.getAllPatches()).thenReturn(Collections.singletonMap("demo-ling", policy));

        ApplicationListener<ApplicationReadyEvent> listener = new LingFrameCoreConfiguration()
                .governancePermissionRestoreListener(registry, permissionService);
        listener.onApplicationEvent(null);

        InOrder inOrder = inOrder(permissionService);
        inOrder.verify(permissionService).removeLing("demo-ling");
        inOrder.verify(permissionService).grant("demo-ling", Capabilities.STORAGE_SQL, AccessType.WRITE);
        verifyNoMoreInteractions(permissionService);
    }
}
