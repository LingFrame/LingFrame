package com.lingframe.dashboard.alert;

import com.lingframe.core.alert.AlertEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Dashboard 告警通道单元测试
 */
class DashboardAlertChannelTest {

    private final DashboardAlertChannel channel = new DashboardAlertChannel();

    @Test
    @DisplayName("getName 应返回 Dashboard")
    void shouldReturnName() {
        assertEquals("Dashboard", channel.getName());
    }

    @Test
    @DisplayName("shouldSend 应始终返回 true（Dashboard 通道不分级过滤）")
    void shouldAlwaysSend() {
        assertTrue(channel.shouldSend(mock(AlertEvent.class)));
    }

    @Test
    @DisplayName("send 应读取 event 的 level/type/message 用于日志输出，不抛异常")
    void shouldLogEventFieldsWithoutException() {
        AlertEvent event = mock(AlertEvent.class);

        channel.send(event);

        verify(event).getLevel();
        verify(event).getType();
        verify(event).getMessage();
    }
}
