package com.lingframe.core.security;

import com.lingframe.api.security.AccessType;
import com.lingframe.api.security.PermissionAuditRecord;
import com.lingframe.api.security.PermissionAuditResult;
import com.lingframe.core.config.LingFrameConfig;
import com.lingframe.core.event.EventBus;
import com.lingframe.core.event.monitor.MonitoringEvents;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(MockitoExtension.class)
@DisplayName("DefaultPermissionService 测试")
class DefaultPermissionServiceTest {

    private EventBus eventBus;
    private DefaultPermissionService permissionService;

    @BeforeEach
    void setUp() {
        eventBus = new EventBus();
        permissionService = new DefaultPermissionService(eventBus);
    }

    @AfterEach
    void tearDown() {
        LingFrameConfig.current().setDevMode(false);
    }

    @Nested
    @DisplayName("全局白名单")
    class GlobalWhitelistTests {
        @Test
        @DisplayName("应允许访问全局白名单 API")
        void shouldAllowGlobalWhitelist() {
            assertTrue(permissionService.isAllowed("any-ling", "com.lingframe.api.SomeApi", AccessType.READ));
        }
    }

    @Nested
    @DisplayName("权限授予与检查")
    class GrantCheckTests {
        @Test
        @DisplayName("授予和检查权限应支持直接与层级访问")
        void shouldGrantAndCheckPermission() {
            String lingId = "test-ling";
            String capability = "test-capability";
            AccessType accessType = AccessType.WRITE;

            assertFalse(permissionService.isAllowed(lingId, capability, accessType));

            permissionService.grant(lingId, capability, accessType);

            assertTrue(permissionService.isAllowed(lingId, capability, accessType));
            assertTrue(permissionService.isAllowed(lingId, capability, AccessType.READ));
        }

        @Test
        @DisplayName("获取权限应返回已授予的权限")
        void shouldGetPermission() {
            String lingId = "test-ling";
            String capability = "test-capability";

            assertNull(permissionService.getPermission(lingId, capability));

            permissionService.grant(lingId, capability, AccessType.WRITE);

            assertNotNull(permissionService.getPermission(lingId, capability));
        }
    }

    @Nested
    @DisplayName("开发模式")
    class DevModeTests {
        @Test
        @DisplayName("开发模式下即使没有权限也应允许访问")
        void shouldAllowAllInDevMode() {
            LingFrameConfig.current().setDevMode(true);
            assertTrue(permissionService.isAllowed("test-ling", "test-capability", AccessType.WRITE));
        }
    }

    @Nested
    @DisplayName("审计事件")
    class AuditTests {
        @Test
        @DisplayName("应发布结构化的三态审计事件")
        void publishesStructuredAuditEvent() {
            AtomicReference<MonitoringEvents.AuditLogEvent> captured = new AtomicReference<>();
            eventBus.subscribe("test-listener", MonitoringEvents.AuditLogEvent.class, captured::set);

            permissionService.audit(PermissionAuditRecord.builder()
                    .callerLingId("ling-a")
                    .principal("alice")
                    .capability("storage:sql")
                    .action("PUT /orders/1")
                    .resource("PUT /orders/1")
                    .result(PermissionAuditResult.FAILED)
                    .failureReason("IllegalStateException: boom")
                    .costNanos(1234L)
                    .build());

            MonitoringEvents.AuditLogEvent event = captured.get();
            assertNotNull(event);
            assertEquals("ling-a", event.getLingId());
            assertEquals("alice", event.getPrincipal());
            assertEquals("storage:sql", event.getCapability());
            assertEquals(PermissionAuditResult.FAILED, event.getResult());
            assertEquals("IllegalStateException: boom", event.getFailureReason());
            assertEquals(1234L, event.getCostNanos());
            assertFalse(event.isSuccess());
        }
    }
}
