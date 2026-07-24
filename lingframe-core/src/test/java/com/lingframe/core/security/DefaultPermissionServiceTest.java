package com.lingframe.core.security;

import com.lingframe.api.constant.LingCoreConstants;
import com.lingframe.api.context.LingCallContext;
import com.lingframe.api.security.AccessType;
import com.lingframe.api.security.PermissionAuditRecord;
import com.lingframe.api.security.PermissionAuditResult;
import com.lingframe.core.config.LingFrameConfig;
import com.lingframe.core.event.EventBus;
import com.lingframe.core.event.monitor.MonitoringEvents;
import com.lingframe.core.pipeline.InvocationContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
        // 配置已 immutable，直接通过 builder 构建局部实例，不依赖全局单例
        permissionService = new DefaultPermissionService(eventBus,
                LingFrameConfig.builder().devMode(false).build());
    }

    @AfterEach
    void tearDown() {
        LingCallContext.clear();
        InvocationContext.detach(null);
        eventBus.shutdown();
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
            DefaultPermissionService devModeService = new DefaultPermissionService(eventBus,
                    LingFrameConfig.builder().devMode(true).build());
            assertTrue(devModeService.isAllowed("test-ling", "test-capability", AccessType.WRITE));
        }

        @Test
        @DisplayName("开发模式旁路应发布 DEV_PERMISSION_BYPASS 告警")
        void shouldPublishDevModeBypassAlert() {
            EventCapture<MonitoringEvents.AlertNotifyEvent> captured = new EventCapture<>();
            eventBus.subscribe("test-listener", MonitoringEvents.AlertNotifyEvent.class, captured::set);
            InvocationContext ctx = attachContext("trace-dev");

            DefaultPermissionService devModeService = new DefaultPermissionService(eventBus,
                    LingFrameConfig.builder().devMode(true).build());

            try {
                assertTrue(devModeService.isAllowed("test-ling", "test-capability", AccessType.WRITE));
            } finally {
                InvocationContext.detach(null);
                ctx.recycle();
            }

            MonitoringEvents.AlertNotifyEvent event = captured.await(Duration.ofSeconds(2));
            assertNotNull(event);
            assertEquals("trace-dev", event.getTraceId());
            assertEquals("WARNING", event.getLevel());
            assertEquals("DEV_PERMISSION_BYPASS", event.getType());
            assertEquals("test-ling", event.getLingId());
            assertTrue(event.getMessage().contains("test-capability"));
            assertEquals("test-ling:test-service#createOrder", event.getSource());
            assertEquals("AnnotationPolicy", event.getRuleSource());
        }
    }

    @Nested
    @DisplayName("灵核身份豁免")
    class LingCoreIdentityTests {
        @Test
        @DisplayName("灵核身份 + check-permissions=false（默认）时豁免灵元权限表（不是灵元、无 ling.yml 声明）")
        void lingCoreIdentityBypassesWhenCheckPermissionsDisabled() {
            // 默认配置：lingCoreCheckPermissions=false，灵核身份豁免灵元权限表
            DefaultPermissionService defaultService = new DefaultPermissionService(eventBus,
                    LingFrameConfig.builder()
                            .devMode(false)
                            .lingCoreCheckPermissions(false)
                            .build());
            assertTrue(defaultService.isAllowed(
                    LingCoreConstants.LINGCORE_LING_ID, "lingcore:bean:read", AccessType.READ));
        }

        @Test
        @DisplayName("灵核身份 + check-permissions=true 加固时仍走权限表 enforce（toggle 控基础设施代理表面）")
        void lingCoreIdentityEnforcedWhenCheckPermissionsEnabled() {
            // 加固 toggle 开启：模拟操作员设 ling-core-governance.check-permissions: true
            // 基础设施代理（cache/SQL/Redis）直接调 isAllowed 不走 PermissionGovernanceFilter，
            // toggle 必须在此 gate 才能控制这表面——灵核身份也走权限表 enforce。
            DefaultPermissionService hardenedService = new DefaultPermissionService(eventBus,
                    LingFrameConfig.builder()
                            .devMode(false)
                            .lingCoreCheckPermissions(true)
                            .build());
            // 灵核身份未声明此 capability → 加固 toggle 开启时应 enforce 拒绝
            assertFalse(hardenedService.isAllowed(
                    LingCoreConstants.LINGCORE_LING_ID, "lingcore:bean:read", AccessType.READ));
        }

        @Test
        @DisplayName("灵核身份 + check-permissions=true 加固 + 显式 grant 后放行（加固路径可声明授权）")
        void lingCoreIdentityAllowedAfterExplicitGrantWhenHardened() {
            DefaultPermissionService hardenedService = new DefaultPermissionService(eventBus,
                    LingFrameConfig.builder()
                            .devMode(false)
                            .lingCoreCheckPermissions(true)
                            .build());
            hardenedService.grant(LingCoreConstants.LINGCORE_LING_ID, "lingcore:bean:read", AccessType.READ);
            assertTrue(hardenedService.isAllowed(
                    LingCoreConstants.LINGCORE_LING_ID, "lingcore:bean:read", AccessType.READ));
        }

        @Test
        @DisplayName("灵元 caller 调灵核 Bean 在 check-permissions=true 加固时仍走权限表 enforce")
        void lingCallerEnforcedWhenCheckPermissionsEnabled() {
            DefaultPermissionService hardenedService = new DefaultPermissionService(eventBus,
                    LingFrameConfig.builder()
                            .devMode(false)
                            .lingCoreCheckPermissions(true)
                            .build());
            // 灵元 caller 没声明这个 capability → 加固 toggle 开启时应 enforce 拒绝
            assertFalse(hardenedService.isAllowed(
                    "caller-ling", "lingcore:bean:read", AccessType.READ));
        }

        @Test
        @DisplayName("灵元 caller 调灵核 Bean 在 check-permissions=false 时 dev 模式放行 + 告警")
        void lingCallerDevModeBypassWhenCheckPermissionsDisabled() {
            DefaultPermissionService relaxedService = new DefaultPermissionService(eventBus,
                    LingFrameConfig.builder()
                            .devMode(true)
                            .lingCoreCheckPermissions(false)
                            .build());
            // dev 模式 + 灵元 caller 未声明权限 → 放行 + 告警（DEV WARNING）
            assertTrue(relaxedService.isAllowed(
                    "caller-ling", "lingcore:bean:read", AccessType.READ));
        }
    }

    @Nested
    @DisplayName("审计事件")
    class AuditTests {
        @Test
        @DisplayName("应发布结构化的三态审计事件")
        void publishesStructuredAuditEvent() {
            EventCapture<MonitoringEvents.AuditLogEvent> captured = new EventCapture<>();
            eventBus.subscribe("test-listener", MonitoringEvents.AuditLogEvent.class, captured::set);
            InvocationContext ctx = attachContext("trace-audit");

            try {
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
            } finally {
                InvocationContext.detach(null);
                ctx.recycle();
            }

            MonitoringEvents.AuditLogEvent event = captured.await(Duration.ofSeconds(2));
            assertNotNull(event);
            assertEquals("trace-audit", event.getTraceId());
            assertEquals("ling-a", event.getLingId());
            assertEquals("alice", event.getPrincipal());
            assertEquals("storage:sql", event.getCapability());
            assertEquals("test-ling:test-service#createOrder", event.getSource());
            assertEquals("AnnotationPolicy", event.getRuleSource());
            assertEquals(PermissionAuditResult.FAILED, event.getResult());
            assertEquals("IllegalStateException: boom", event.getFailureReason());
            assertEquals(1234L, event.getCostNanos());
            assertFalse(event.isSuccess());
        }
    }

    private InvocationContext attachContext(String traceId) {
        InvocationContext ctx = InvocationContext.obtain();
        ctx.setTraceId(traceId);
        ctx.setServiceFQSID("test-ling:test-service");
        ctx.setOperation("createOrder");
        ctx.setResourceId("POST /orders");
        ctx.governance().setRuleSource("AnnotationPolicy");
        ctx.attach();
        return ctx;
    }

    /**
     * 事件捕获器：基于 CountDownLatch 替代轮询等待，事件到达即唤醒，无需周期性 sleep。
     */
    static final class EventCapture<T> {
        private final AtomicReference<T> ref = new AtomicReference<>();
        private final CountDownLatch latch = new CountDownLatch(1);

        void set(T event) {
            ref.set(event);
            latch.countDown();
        }

        T await(Duration timeout) {
            try {
                latch.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return ref.get();
        }
    }
}
