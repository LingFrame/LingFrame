package com.lingframe.core.alert;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AlertManager 测试。
 * 覆盖：通道注册/触发/历史查询/清空。
 */
@DisplayName("AlertManager 测试")
class AlertManagerTest {

    private AlertManager alertManager;

    @BeforeEach
    void setUp() {
        alertManager = new AlertManager();
    }

    private AlertChannel createChannel(String name) {
        return new AlertChannel() {
            @Override
            public String getName() { return name; }

            @Override
            public boolean shouldSend(AlertEvent event) { return true; }

            @Override
            public void send(AlertEvent event) {}
        };
    }

    // ==================== 通道注册 ====================

    @Nested
    @DisplayName("通道注册")
    class ChannelRegister {

        @Test
        @DisplayName("注册通道后触发告警不抛异常")
        void registerChannelAndTrigger() {
            AlertChannel channel = createChannel("log");
            alertManager.registerChannel(channel);

            AlertEvent event = AlertEvent.info(AlertEvent.AlertType.LING_STATUS_CHANGED, "ling-1", "Test");
            assertDoesNotThrow(() -> alertManager.triggerAlert(event));
        }

        @Test
        @DisplayName("注销通道后不再接收告警")
        void unregisterChannel() {
            AlertChannel channel = createChannel("log");
            alertManager.registerChannel(channel);
            alertManager.unregisterChannel(channel);

            // 注销后触发不抛异常
            AlertEvent event = AlertEvent.info(AlertEvent.AlertType.LING_STATUS_CHANGED, "ling-1", "Test");
            assertDoesNotThrow(() -> alertManager.triggerAlert(event));
        }
    }

    // ==================== 触发告警 ====================

    @Nested
    @DisplayName("触发告警")
    class TriggerAlert {

        @Test
        @DisplayName("触发告警发送到已注册通道")
        void alertSentToChannel() {
            StringBuilder sb = new StringBuilder();
            AlertChannel channel = new AlertChannel() {
                @Override public String getName() { return "test"; }
                @Override public boolean shouldSend(AlertEvent event) { return true; }
                @Override public void send(AlertEvent event) { sb.append(event.getMessage()); }
            };
            alertManager.registerChannel(channel);

            alertManager.triggerAlert(AlertEvent.error(AlertEvent.AlertType.LING_START_FAILED, "ling-1", "Start failed"));
            assertEquals("Start failed", sb.toString());
        }

        @Test
        @DisplayName("shouldSend 返回 false 时不发送")
        void shouldSendFalseSkips() {
            StringBuilder sb = new StringBuilder();
            AlertChannel channel = new AlertChannel() {
                @Override public String getName() { return "filtered"; }
                @Override public boolean shouldSend(AlertEvent event) { return false; }
                @Override public void send(AlertEvent event) { sb.append("sent"); }
            };
            alertManager.registerChannel(channel);

            alertManager.triggerAlert(AlertEvent.info(AlertEvent.AlertType.LING_STATUS_CHANGED, "ling-1", "Test"));
            assertEquals("", sb.toString());
        }

        @Test
        @DisplayName("无通道时触发告警不抛异常")
        void alertWithNoChannelsSafe() {
            AlertEvent event = AlertEvent.info(AlertEvent.AlertType.LING_STATUS_CHANGED, "ling-1", "No channels");
            assertDoesNotThrow(() -> alertManager.triggerAlert(event));
        }

        @Test
        @DisplayName("通道发送异常不阻塞其他通道")
        void channelExceptionDoesNotBlockOthers() {
            StringBuilder sb = new StringBuilder();

            AlertChannel failing = new AlertChannel() {
                @Override public String getName() { return "failing"; }
                @Override public boolean shouldSend(AlertEvent event) { return true; }
                @Override public void send(AlertEvent event) { throw new RuntimeException("send error"); }
            };
            AlertChannel normal = new AlertChannel() {
                @Override public String getName() { return "normal"; }
                @Override public boolean shouldSend(AlertEvent event) { return true; }
                @Override public void send(AlertEvent event) { sb.append("ok"); }
            };

            alertManager.registerChannel(failing);
            alertManager.registerChannel(normal);

            assertDoesNotThrow(() ->
                    alertManager.triggerAlert(AlertEvent.error(AlertEvent.AlertType.LING_START_FAILED, "ling-1", "Test")));
            assertEquals("ok", sb.toString());
        }
    }

    // ==================== 历史查询 ====================

    @Nested
    @DisplayName("历史查询")
    class HistoryQuery {

        @Test
        @DisplayName("告警记录在历史中")
        void alertRecordedInHistory() {
            alertManager.triggerAlert(AlertEvent.error(AlertEvent.AlertType.LING_START_FAILED, "ling-1", "Error 1"));
            alertManager.triggerAlert(AlertEvent.warning(AlertEvent.AlertType.LING_UNHEALTHY, "ling-2", "Warning 1"));

            List<AlertEvent> history = alertManager.getAlertHistory();
            assertEquals(2, history.size());
        }

        @Test
        @DisplayName("按 lingId 过滤历史")
        void filterByLingId() {
            alertManager.triggerAlert(AlertEvent.error(AlertEvent.AlertType.LING_START_FAILED, "ling-1", "Error 1"));
            alertManager.triggerAlert(AlertEvent.warning(AlertEvent.AlertType.LING_UNHEALTHY, "ling-2", "Warning 1"));
            alertManager.triggerAlert(AlertEvent.error(AlertEvent.AlertType.LING_STOP_FAILED, "ling-1", "Error 2"));

            List<AlertEvent> filtered = alertManager.getAlertsByLing("ling-1");
            assertEquals(2, filtered.size());
            filtered.forEach(e -> assertEquals("ling-1", e.getLingId()));
        }

        @Test
        @DisplayName("按级别过滤历史")
        void filterByLevel() {
            alertManager.triggerAlert(AlertEvent.error(AlertEvent.AlertType.LING_START_FAILED, "ling-1", "Error"));
            alertManager.triggerAlert(AlertEvent.warning(AlertEvent.AlertType.LING_UNHEALTHY, "ling-2", "Warning"));
            alertManager.triggerAlert(AlertEvent.info(AlertEvent.AlertType.LING_STATUS_CHANGED, "ling-3", "Info"));

            List<AlertEvent> errors = alertManager.getAlertsByLevel(AlertEvent.AlertLevel.ERROR);
            assertEquals(1, errors.size());
            assertEquals(AlertEvent.AlertLevel.ERROR, errors.get(0).getLevel());
        }

        @Test
        @DisplayName("无历史时返回空列表")
        void noHistoryReturnsEmpty() {
            assertTrue(alertManager.getAlertHistory().isEmpty());
        }
    }

    // ==================== 清空 ====================

    @Nested
    @DisplayName("清空")
    class Clear {

        @Test
        @DisplayName("clearHistory 清空所有历史记录")
        void clearHistory() {
            alertManager.triggerAlert(AlertEvent.error(AlertEvent.AlertType.LING_START_FAILED, "ling-1", "Error"));
            assertFalse(alertManager.getAlertHistory().isEmpty());

            alertManager.clearHistory();
            assertTrue(alertManager.getAlertHistory().isEmpty());
        }
    }

    // ==================== 历史容量 ====================

    @Nested
    @DisplayName("历史容量")
    class HistoryCapacity {

        @Test
        @DisplayName("超过容量的旧记录被丢弃")
        void overflowDiscardsOldest() {
            AlertManager mgr = new AlertManager(3);
            for (int i = 0; i < 5; i++) {
                mgr.triggerAlert(AlertEvent.info(AlertEvent.AlertType.LING_STATUS_CHANGED, "ling-1", "msg-" + i));
            }

            List<AlertEvent> history = mgr.getAlertHistory();
            assertEquals(3, history.size());
            // 保留最新的 3 条
            assertEquals("msg-4", history.get(2).getMessage());
        }
    }
}
