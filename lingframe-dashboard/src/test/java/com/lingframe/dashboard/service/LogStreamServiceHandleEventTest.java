package com.lingframe.dashboard.service;

import com.lingframe.api.event.lifecycle.LingInstalledEvent;
import com.lingframe.api.event.lifecycle.LingInstallingEvent;
import com.lingframe.api.event.lifecycle.LingUninstalledEvent;
import com.lingframe.api.event.lifecycle.LingUninstallingEvent;
import com.lingframe.api.security.PermissionAuditResult;
import com.lingframe.core.event.EventBus;
import com.lingframe.core.event.InstanceDestroyedEvent;
import com.lingframe.core.event.InstanceStateChangedEvent;
import com.lingframe.core.event.RuntimeStateChangedEvent;
import com.lingframe.core.event.monitor.MonitoringEvents;
import com.lingframe.core.fsm.InstanceStatus;
import com.lingframe.core.fsm.RuntimeStatus;
import com.lingframe.dashboard.dto.LogStreamDTO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.File;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

/**
 * LogStreamService handle* 事件处理方法补充测试
 * <p>
 * 现有 LogStreamServiceTest 仅覆盖 afterPropertiesSet/createEmitter/broadcast/destroy。
 * 本类用反射调用 13 个 private handle* 方法，验证 LogStreamDTO 格式化逻辑
 * 以及 resolveAuditLevel 的 ALLOWED/DENIED/FAILED/null 分支。
 */
@DisplayName("LogStreamService handle* 事件处理测试")
class LogStreamServiceHandleEventTest {

    private LogStreamService service;
    private LogStreamService spy;

    @BeforeEach
    void setUp() {
        EventBus eventBus = new EventBus();
        service = new LogStreamService(eventBus);
        spy = spy(service);
        // 禁用真实 broadcast（避免异步线程干扰断言）
        doNothing().when(spy).broadcast(org.mockito.ArgumentMatchers.any());
    }

    @AfterEach
    void tearDown() {
        service.destroy();
    }

    private LogStreamDTO capture(Runnable invocation) {
        ArgumentCaptor<LogStreamDTO> captor = ArgumentCaptor.forClass(LogStreamDTO.class);
        invocation.run();
        verify(spy).broadcast(captor.capture());
        return captor.getValue();
    }

    private void invokePrivate(String methodName, Class<?> paramType, Object arg) throws Exception {
        Method m = LogStreamService.class.getDeclaredMethod(methodName, paramType);
        m.setAccessible(true);
        m.invoke(spy, arg);
    }

    // ==================== handleTrace ====================

    @Nested
    @DisplayName("handleTrace")
    class HandleTraceTests {

        @Test
        @DisplayName("应格式化 Trace 日志为 TRACE 类型")
        void shouldFormatTraceEvent() throws Exception {
            MonitoringEvents.TraceLogEvent event = new MonitoringEvents.TraceLogEvent(
                    "trace1", "ling1", "invokeMethod", "DB", 2);

            LogStreamDTO dto = capture(() -> {
                try {
                    invokePrivate("handleTrace", MonitoringEvents.TraceLogEvent.class, event);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            assertEquals("TRACE", dto.getType());
            assertEquals("trace1", dto.getTraceId());
            assertEquals("ling1", dto.getLingId());
            assertEquals("invokeMethod", dto.getContent());
            assertEquals("DB", dto.getTag());
            assertEquals(2, dto.getDepth());
        }
    }

    // ==================== handleAudit ====================

    @Nested
    @DisplayName("handleAudit")
    class HandleAuditTests {

        @Test
        @DisplayName("ALLOWED 结果应映射为 INFO 级别")
        void shouldMapAllowedToInfo() throws Exception {
            MonitoringEvents.AuditLogEvent event = new MonitoringEvents.AuditLogEvent(
                    "trace1", "ling1", "user1", "read", "db:users",
                    "storage:sql", "dashboard", "rule1",
                    PermissionAuditResult.ALLOWED, null, 1_000_000L);

            LogStreamDTO dto = capture(() -> {
                try {
                    invokePrivate("handleAudit", MonitoringEvents.AuditLogEvent.class, event);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            assertEquals("AUDIT", dto.getType());
            assertEquals("ling1", dto.getLingId());
            assertEquals("ALLOWED", dto.getTag());
            assertEquals("INFO", dto.getLevel());
            // content 应包含 action、resource、result、耗时、capability、principal、source、ruleSource
            assertTrue(dto.getContent().contains("read"));
            assertTrue(dto.getContent().contains("db:users"));
            assertTrue(dto.getContent().contains("[storage:sql]"));
            assertTrue(dto.getContent().contains("principal=user1"));
            assertTrue(dto.getContent().contains("source=dashboard"));
            assertTrue(dto.getContent().contains("ruleSource=rule1"));
            assertTrue(dto.getContent().contains("ms)"));
        }

        @Test
        @DisplayName("DENIED 结果应映射为 WARNING 级别")
        void shouldMapDeniedToWarning() throws Exception {
            MonitoringEvents.AuditLogEvent event = new MonitoringEvents.AuditLogEvent(
                    "trace1", "ling1", "read", "db:users", false, 500_000L);

            LogStreamDTO dto = capture(() -> {
                try {
                    invokePrivate("handleAudit", MonitoringEvents.AuditLogEvent.class, event);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            assertEquals("DENIED", dto.getTag());
            assertEquals("WARNING", dto.getLevel());
        }

        @Test
        @DisplayName("FAILED 结果应映射为 ERROR 级别")
        void shouldMapFailedToError() throws Exception {
            MonitoringEvents.AuditLogEvent event = new MonitoringEvents.AuditLogEvent(
                    "trace1", "ling1", null, "write", "db:orders",
                    null, null, null,
                    PermissionAuditResult.FAILED, "connection refused", 2_000_000L);

            LogStreamDTO dto = capture(() -> {
                try {
                    invokePrivate("handleAudit", MonitoringEvents.AuditLogEvent.class, event);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            assertEquals("FAILED", dto.getTag());
            assertEquals("ERROR", dto.getLevel());
            assertTrue(dto.getContent().contains("reason=connection refused"));
            // capability 为 null 不应附加 [capability]
            assertTrue(!dto.getContent().contains("[]"));
        }

        @Test
        @DisplayName("null 结果应映射为 UNKNOWN tag 和 INFO 级别")
        void shouldMapNullResultToUnknown() throws Exception {
            MonitoringEvents.AuditLogEvent event = new MonitoringEvents.AuditLogEvent(
                    "trace1", "ling1", null, "action", "resource",
                    null, null, null, null, null, 0L);

            LogStreamDTO dto = capture(() -> {
                try {
                    invokePrivate("handleAudit", MonitoringEvents.AuditLogEvent.class, event);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            assertEquals("UNKNOWN", dto.getTag());
            assertEquals("INFO", dto.getLevel());
        }
    }

    // ==================== handleAlert ====================

    @Nested
    @DisplayName("handleAlert")
    class HandleAlertTests {

        @Test
        @DisplayName("应格式化告警事件并附加 source 和 ruleSource")
        void shouldFormatAlertWithSourceAndRuleSource() throws Exception {
            MonitoringEvents.AlertNotifyEvent event = new MonitoringEvents.AlertNotifyEvent(
                    "trace1", "ERROR", "HEALTH", "ling1", "health check failed",
                    "monitor", "rule-x");

            LogStreamDTO dto = capture(() -> {
                try {
                    invokePrivate("handleAlert", MonitoringEvents.AlertNotifyEvent.class, event);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            assertEquals("ALERT", dto.getType());
            assertEquals("ling1", dto.getLingId());
            assertEquals("ERROR", dto.getLevel());
            assertEquals("HEALTH", dto.getTag());
            assertTrue(dto.getContent().contains("health check failed"));
            assertTrue(dto.getContent().contains("source=monitor"));
            assertTrue(dto.getContent().contains("ruleSource=rule-x"));
        }

        @Test
        @DisplayName("source 和 ruleSource 为 null 时不应附加")
        void shouldNotAppendNullSource() throws Exception {
            MonitoringEvents.AlertNotifyEvent event = new MonitoringEvents.AlertNotifyEvent(
                    "WARN", "HEALTH", "ling1", "degraded");

            LogStreamDTO dto = capture(() -> {
                try {
                    invokePrivate("handleAlert", MonitoringEvents.AlertNotifyEvent.class, event);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            assertEquals("degraded", dto.getContent());
        }
    }

    // ==================== handleCircuitBreaker ====================

    @Nested
    @DisplayName("handleCircuitBreaker")
    class HandleCircuitBreakerTests {

        @Test
        @DisplayName("应格式化熔断器状态变化")
        void shouldFormatCircuitBreakerEvent() throws Exception {
            MonitoringEvents.CircuitBreakerStateEvent event = new MonitoringEvents.CircuitBreakerStateEvent(
                    "res1", "CLOSED", "OPEN", 0.75);

            LogStreamDTO dto = capture(() -> {
                try {
                    invokePrivate("handleCircuitBreaker", MonitoringEvents.CircuitBreakerStateEvent.class, event);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            assertEquals("ALERT", dto.getType());
            assertEquals("CIRCUIT_BREAKER", dto.getTag());
            assertEquals("WARNING", dto.getLevel());
            assertTrue(dto.getContent().contains("res1"));
            assertTrue(dto.getContent().contains("CLOSED"));
            assertTrue(dto.getContent().contains("OPEN"));
            assertTrue(dto.getContent().contains("75.0%"));
        }
    }

    // ==================== handleLeakDetection ====================

    @Nested
    @DisplayName("handleLeakDetection")
    class HandleLeakDetectionTests {

        @Test
        @DisplayName("collected=true 应映射为 INFO 级别和 OK tag")
        void shouldMapCollectedToInfo() throws Exception {
            MonitoringEvents.LeakDetectionEvent event = new MonitoringEvents.LeakDetectionEvent(
                    "ling1", "1.0.0", true, "leak cleaned", "GC", 1000L);

            LogStreamDTO dto = capture(() -> {
                try {
                    invokePrivate("handleLeakDetection", MonitoringEvents.LeakDetectionEvent.class, event);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            assertEquals("LEAK_DETECTION", dto.getType());
            assertEquals("ling1", dto.getLingId());
            assertEquals("OK", dto.getTag());
            assertEquals("INFO", dto.getLevel());
            assertTrue(dto.getContent().contains("ling1"));
            assertTrue(dto.getContent().contains("1.0.0"));
            assertTrue(dto.getContent().contains("GC"));
            assertTrue(dto.getContent().contains("leak cleaned"));
        }

        @Test
        @DisplayName("collected=false 应映射为 ERROR 级别和 FAIL tag")
        void shouldMapNotCollectedToError() throws Exception {
            MonitoringEvents.LeakDetectionEvent event = new MonitoringEvents.LeakDetectionEvent(
                    "ling1", "1.0.0", false, "leak persists", "GC", 1000L);

            LogStreamDTO dto = capture(() -> {
                try {
                    invokePrivate("handleLeakDetection", MonitoringEvents.LeakDetectionEvent.class, event);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            assertEquals("FAIL", dto.getTag());
            assertEquals("ERROR", dto.getLevel());
        }
    }

    // ==================== handleCleanupCapability ====================

    @Nested
    @DisplayName("handleCleanupCapability")
    class HandleCleanupCapabilityTests {

        @Test
        @DisplayName("应格式化资源清理能力事件")
        void shouldFormatCleanupCapabilityEvent() throws Exception {
            MonitoringEvents.ResourceCleanupCapabilityEvent event =
                    new MonitoringEvents.ResourceCleanupCapabilityEvent(
                            "BasicUnloadHook", 17, false, false, false, false, false, "jdk=17");

            LogStreamDTO dto = capture(() -> {
                try {
                    invokePrivate("handleCleanupCapability", MonitoringEvents.ResourceCleanupCapabilityEvent.class, event);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            assertEquals("RUNTIME_DIAGNOSTIC", dto.getType());
            assertEquals("RESOURCE_CLEANUP_CAPABILITY", dto.getTag());
            assertEquals("INFO", dto.getLevel());
            assertTrue(dto.getContent().contains("BasicUnloadHook"));
            assertTrue(dto.getContent().contains("jdk=17"));
        }
    }

    // ==================== handleInstanceStateChange ====================

    @Nested
    @DisplayName("handleInstanceStateChange")
    class HandleInstanceStateChangeTests {

        @Test
        @DisplayName("目标状态含 ERROR 应映射为 ERROR 级别")
        void shouldMapErrorStatusToErrorLevel() throws Exception {
            InstanceStateChangedEvent event = new InstanceStateChangedEvent(
                    "ling1", "1.0.0", InstanceStatus.READY, InstanceStatus.ERROR);

            LogStreamDTO dto = capture(() -> {
                try {
                    invokePrivate("handleInstanceStateChange", InstanceStateChangedEvent.class, event);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            assertEquals("ALERT", dto.getType());
            assertEquals("ling1", dto.getLingId());
            assertEquals("1.0.0", dto.getVersion());
            assertEquals("STATE_CHANGE", dto.getTag());
            assertEquals("ERROR", dto.getLevel());
            assertTrue(dto.getContent().contains("READY"));
            assertTrue(dto.getContent().contains("ERROR"));
        }

        @Test
        @DisplayName("目标状态为 STOPPING 不含 STOPPED 关键字，应为 INFO 级别")
        void shouldMapStoppingStatusToInfoLevel() throws Exception {
            // 代码检查 toStatus.name().contains("STOPPED")，
            // STOPPING 不包含 STOPPED，所以不匹配 WARNING，保持 INFO
            InstanceStateChangedEvent event = new InstanceStateChangedEvent(
                    "ling1", "1.0.0", InstanceStatus.READY, InstanceStatus.STOPPING);

            LogStreamDTO dto = capture(() -> {
                try {
                    invokePrivate("handleInstanceStateChange", InstanceStateChangedEvent.class, event);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            assertEquals("INFO", dto.getLevel());
        }

        @Test
        @DisplayName("目标状态为 READY 应映射为 INFO 级别")
        void shouldMapReadyStatusToInfoLevel() throws Exception {
            InstanceStateChangedEvent event = new InstanceStateChangedEvent(
                    "ling1", "1.0.0", InstanceStatus.CREATED, InstanceStatus.READY);

            LogStreamDTO dto = capture(() -> {
                try {
                    invokePrivate("handleInstanceStateChange", InstanceStateChangedEvent.class, event);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            assertEquals("INFO", dto.getLevel());
        }
    }

    // ==================== handleRuntimeStateChange ====================

    @Nested
    @DisplayName("handleRuntimeStateChange")
    class HandleRuntimeStateChangeTests {

        @Test
        @DisplayName("运行时状态切换到 INACTIVE 应映射为 WARNING 级别")
        void shouldMapInactiveToWarning() throws Exception {
            RuntimeStateChangedEvent event = new RuntimeStateChangedEvent(
                    "ling1", RuntimeStatus.ACTIVE, RuntimeStatus.INACTIVE);

            LogStreamDTO dto = capture(() -> {
                try {
                    invokePrivate("handleRuntimeStateChange", RuntimeStateChangedEvent.class, event);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            assertEquals("ALERT", dto.getType());
            assertEquals("ling1", dto.getLingId());
            assertEquals("RUNTIME_CHANGE", dto.getTag());
            assertEquals("WARNING", dto.getLevel());
            assertTrue(dto.getContent().contains("ACTIVE"));
            assertTrue(dto.getContent().contains("INACTIVE"));
        }

        @Test
        @DisplayName("运行时状态切换到 ACTIVE 应映射为 INFO 级别")
        void shouldMapActiveToInfo() throws Exception {
            RuntimeStateChangedEvent event = new RuntimeStateChangedEvent(
                    "ling1", RuntimeStatus.INACTIVE, RuntimeStatus.ACTIVE);

            LogStreamDTO dto = capture(() -> {
                try {
                    invokePrivate("handleRuntimeStateChange", RuntimeStateChangedEvent.class, event);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            assertEquals("INFO", dto.getLevel());
        }
    }

    // ==================== handleInstanceDestroyed ====================

    @Nested
    @DisplayName("handleInstanceDestroyed")
    class HandleInstanceDestroyedTests {

        @Test
        @DisplayName("应格式化实例销毁事件")
        void shouldFormatInstanceDestroyedEvent() throws Exception {
            InstanceDestroyedEvent event = new InstanceDestroyedEvent("ling1", "1.0.0");

            LogStreamDTO dto = capture(() -> {
                try {
                    invokePrivate("handleInstanceDestroyed", InstanceDestroyedEvent.class, event);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            assertEquals("ALERT", dto.getType());
            assertEquals("ling1", dto.getLingId());
            assertEquals("1.0.0", dto.getVersion());
            assertEquals("INSTANCE_DESTROYED", dto.getTag());
            assertEquals("WARNING", dto.getLevel());
            assertTrue(dto.getContent().contains("destroyed"));
        }
    }

    // ==================== 生命周期事件 ====================

    @Nested
    @DisplayName("生命周期事件")
    class LifecycleEventTests {

        @Test
        @DisplayName("handleLingInstalling 应格式化安装中事件")
        void shouldFormatLingInstallingEvent() throws Exception {
            LingInstallingEvent event = new LingInstallingEvent("ling1", "1.0.0", new File("test.jar"));

            LogStreamDTO dto = capture(() -> {
                try {
                    invokePrivate("handleLingInstalling", LingInstallingEvent.class, event);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            assertEquals("ALERT", dto.getType());
            assertEquals("ling1", dto.getLingId());
            assertEquals("1.0.0", dto.getVersion());
            assertEquals("LING_INSTALLING", dto.getTag());
            assertEquals("INFO", dto.getLevel());
            assertTrue(dto.getContent().contains("installing"));
        }

        @Test
        @DisplayName("handleLingInstalled 应格式化安装完成事件")
        void shouldFormatLingInstalledEvent() throws Exception {
            LingInstalledEvent event = new LingInstalledEvent("ling1", "1.0.0");

            LogStreamDTO dto = capture(() -> {
                try {
                    invokePrivate("handleLingInstalled", LingInstalledEvent.class, event);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            assertEquals("LING_INSTALLED", dto.getTag());
            assertEquals("INFO", dto.getLevel());
            assertTrue(dto.getContent().contains("installed successfully"));
        }

        @Test
        @DisplayName("handleLingUninstalling 应格式化卸载中事件")
        void shouldFormatLingUninstallingEvent() throws Exception {
            LingUninstallingEvent event = new LingUninstallingEvent("ling1");

            LogStreamDTO dto = capture(() -> {
                try {
                    invokePrivate("handleLingUninstalling", LingUninstallingEvent.class, event);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            assertEquals("LING_UNINSTALLING", dto.getTag());
            assertEquals("WARNING", dto.getLevel());
            assertTrue(dto.getContent().contains("uninstalling"));
        }

        @Test
        @DisplayName("handleLingUninstalled 应格式化卸载完成事件")
        void shouldFormatLingUninstalledEvent() throws Exception {
            LingUninstalledEvent event = new LingUninstalledEvent("ling1");

            LogStreamDTO dto = capture(() -> {
                try {
                    invokePrivate("handleLingUninstalled", LingUninstalledEvent.class, event);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            assertEquals("LING_UNINSTALLED", dto.getTag());
            assertEquals("INFO", dto.getLevel());
            assertTrue(dto.getContent().contains("uninstalled successfully"));
        }
    }

    // ==================== sendHeartbeat / removeEmitter / withCoreClassLoader ====================

    @Nested
    @DisplayName("心跳与连接管理")
    class HeartbeatAndConnectionTests {

        @Test
        @DisplayName("sendHeartbeat 在 emitters 为空时应直接返回不提交任务")
        void sendHeartbeatShouldReturnImmediatelyWhenNoEmitters() throws Exception {
            // emitters 为空，sendHeartbeat 直接 return
            Method m = LogStreamService.class.getDeclaredMethod("sendHeartbeat");
            m.setAccessible(true);
            // 不应抛异常
            m.invoke(spy);
        }

        @Test
        @DisplayName("removeEmitter 应从列表移除指定 emitter")
        void removeEmitterShouldRemoveFromList() throws Exception {
            // 先创建一个真实 emitter 并加入列表
            SseEmitter emitter = new SseEmitter(0L);
            java.lang.reflect.Field f = LogStreamService.class.getDeclaredField("emitters");
            f.setAccessible(true);
            @SuppressWarnings("unchecked")
            java.util.List<SseEmitter> emitters = (java.util.List<SseEmitter>) f.get(spy);
            emitters.add(emitter);

            Method m = LogStreamService.class.getDeclaredMethod("removeEmitter", SseEmitter.class);
            m.setAccessible(true);
            m.invoke(spy, emitter);

            assertTrue(emitters.isEmpty());
        }

        @Test
        @DisplayName("withCoreClassLoader 应在执行期间设置 ContextClassLoader")
        void withCoreClassLoaderShouldSetClassLoader() throws Exception {
            ClassLoader original = Thread.currentThread().getContextClassLoader();
            Method m = LogStreamService.class.getDeclaredMethod("withCoreClassLoader", Runnable.class);
            m.setAccessible(true);

            java.util.concurrent.atomic.AtomicReference<ClassLoader> captured = new java.util.concurrent.atomic.AtomicReference<>();
            Runnable task = () -> captured.set(Thread.currentThread().getContextClassLoader());
            Runnable wrapped = (Runnable) m.invoke(spy, task);
            wrapped.run();

            // 执行期间应为 LogStreamService 的 ClassLoader
            assertEquals(LogStreamService.class.getClassLoader(), captured.get());
            // 执行后应恢复原 ClassLoader
            assertEquals(original, Thread.currentThread().getContextClassLoader());
        }
    }
}
