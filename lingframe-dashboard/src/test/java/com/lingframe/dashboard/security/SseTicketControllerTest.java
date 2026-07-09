package com.lingframe.dashboard.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * SSE Ticket 控制器单元测试
 * 覆盖：颁发 / 验证消费（一次性） / 过期 / 清理 / token 未启用放行
 */
class SseTicketControllerTest {

    private AccessTokenProperties tokenProps;
    private SseTicketController controller;

    @BeforeEach
    void setUp() {
        tokenProps = mock(AccessTokenProperties.class);
        when(tokenProps.isEnabled()).thenReturn(true);
        controller = new SseTicketController(tokenProps);
    }

    @Nested
    @DisplayName("issueTicket")
    class IssueTicketTests {
        @Test
        @DisplayName("token 未启用时应返回空 ticket")
        void shouldReturnEmptyTicketWhenDisabled() {
            when(tokenProps.isEnabled()).thenReturn(false);
            Map<String, String> result = controller.issueTicket();
            assertEquals("", result.get("ticket"));
        }

        @Test
        @DisplayName("token 启用时应返回非空 UUID ticket（无连字符）")
        void shouldReturnNonEmptyTicketWhenEnabled() {
            String ticket = controller.issueTicket().get("ticket");
            assertTrue(ticket != null && !ticket.isEmpty(), "ticket 应非空");
            assertFalse(ticket.contains("-"), "ticket 应为去连字符的 UUID");
        }

        @Test
        @DisplayName("连续颁发应返回不同 ticket")
        void shouldReturnDifferentTickets() {
            String t1 = controller.issueTicket().get("ticket");
            String t2 = controller.issueTicket().get("ticket");
            assertNotEquals(t1, t2);
        }
    }

    @Nested
    @DisplayName("validateAndConsume")
    class ValidateAndConsumeTests {
        @Test
        @DisplayName("token 未启用时应直接放行")
        void shouldPassWhenDisabled() {
            when(tokenProps.isEnabled()).thenReturn(false);
            assertTrue(controller.validateAndConsume(null));
            assertTrue(controller.validateAndConsume("anything"));
        }

        @Test
        @DisplayName("null 或空 ticket 应拒绝")
        void shouldRejectNullOrEmpty() {
            assertFalse(controller.validateAndConsume(null));
            assertFalse(controller.validateAndConsume(""));
        }

        @Test
        @DisplayName("不存在的 ticket 应拒绝")
        void shouldRejectUnknownTicket() {
            assertFalse(controller.validateAndConsume("non-existent"));
        }

        @Test
        @DisplayName("有效 ticket 应验证通过且一次性消费")
        void shouldValidateAndConsumeOnce() {
            String ticket = controller.issueTicket().get("ticket");
            assertTrue(controller.validateAndConsume(ticket), "首次消费应成功");
            assertFalse(controller.validateAndConsume(ticket), "二次消费应失败");
        }

        @Test
        @DisplayName("过期 ticket（>60s）应拒绝")
        void shouldRejectExpiredTicket() throws Exception {
            String ticket = controller.issueTicket().get("ticket");
            injectIssuedAt(ticket, System.currentTimeMillis() - 70_000);
            assertFalse(controller.validateAndConsume(ticket), "超过 TTL 的 ticket 应拒绝");
        }
    }

    @Nested
    @DisplayName("cleanupExpired")
    class CleanupExpiredTests {
        @Test
        @DisplayName("token 未启用时应直接返回，不操作 tickets")
        void shouldNoopWhenDisabled() {
            when(tokenProps.isEnabled()).thenReturn(false);
            controller.issueTicket();
            controller.cleanupExpired();
        }

        @Test
        @DisplayName("应移除过期 ticket，保留未过期 ticket")
        void shouldRemoveExpiredAndKeepValid() throws Exception {
            String valid = controller.issueTicket().get("ticket");
            String expired = controller.issueTicket().get("ticket");
            injectIssuedAt(expired, System.currentTimeMillis() - 70_000);

            controller.cleanupExpired();

            assertFalse(controller.validateAndConsume(expired), "过期 ticket 应已被清理");
            assertTrue(controller.validateAndConsume(valid), "未过期 ticket 应仍可用");
        }
    }

    /** 反射注入指定 ticket 的签发时间，模拟过期场景 */
    @SuppressWarnings("unchecked")
    private void injectIssuedAt(String ticket, long timestamp) throws Exception {
        Field f = SseTicketController.class.getDeclaredField("tickets");
        f.setAccessible(true);
        Map<String, Long> tickets = (Map<String, Long>) f.get(controller);
        tickets.put(ticket, timestamp);
    }
}
