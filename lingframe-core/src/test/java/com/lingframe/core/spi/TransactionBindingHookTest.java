package com.lingframe.core.spi;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 事务状态提取 SPI 契约测试。
 * <p>
 * 用最小 stub 实现锁定 {@link TransactionBindingHook} 的接口文档承诺：
 * 无活跃事务时空集（不为 null）、按 dataSourceId 提取绑定连接、未绑定返回 null、
 * 身份集合与提取结果一致——runtime starter 的 Spring 实现（TSM 桥接）必须满足本契约，
 * core 的 Pipeline 过滤器（TransactionPropagationFilter）按该契约消费，不感知实现细节。
 */
@DisplayName("TransactionBindingHook 事务状态提取 SPI 契约")
class TransactionBindingHookTest {

    /**
     * 最小内存实现：模拟「按 dataSourceId 绑定的活跃事务」语义。
     * 仅体现接口文档承诺的契约语义，不含任何生态依赖（core 零 Spring）。
     */
    private static final class StubHook implements TransactionBindingHook {
        private final Map<String, Connection> bound = new HashMap<>();
        private boolean active;

        StubHook bind(String dataSourceId, Connection conn) {
            bound.put(dataSourceId, conn);
            return this;
        }

        StubHook active(boolean active) {
            this.active = active;
            return this;
        }

        @Override
        public boolean isTransactionActive() {
            return active;
        }

        @Override
        public Set<String> getActiveBoundDataSourceIds() {
            if (!active) {
                return Collections.emptySet();
            }
            return new LinkedHashSet<>(bound.keySet());
        }

        @Override
        public Connection getBoundConnection(String dataSourceId) {
            return bound.get(dataSourceId);
        }
    }

    @Nested
    @DisplayName("活跃事务判定契约")
    class ActiveTransactionContract {

        @Test
        @DisplayName("无活跃事务时返回 false 且绑定源集合为空（不为 null）")
        void inactiveReturnsFalseAndEmptySet() {
            TransactionBindingHook hook = new StubHook().active(false);

            assertFalse(hook.isTransactionActive());
            assertNotNull(hook.getActiveBoundDataSourceIds());
            assertTrue(hook.getActiveBoundDataSourceIds().isEmpty());
        }

        @Test
        @DisplayName("活跃事务时返回 true 且集合反映全部绑定源")
        void activeReturnsTrueAndIds() {
            TransactionBindingHook hook = new StubHook().active(true)
                    .bind("default", null);

            assertTrue(hook.isTransactionActive());
            assertEquals(Collections.singleton("default"), hook.getActiveBoundDataSourceIds());
        }
    }

    @Nested
    @DisplayName("按 dataSourceId 提取连接契约")
    class BoundConnectionContract {

        @Test
        @DisplayName("提取绑定到指定源的数据源连接视图（同一实例）")
        void extractsBoundConnectionById() {
            Connection conn = org.mockito.Mockito.mock(Connection.class);
            TransactionBindingHook hook = new StubHook().active(true).bind("default", conn);

            assertSame(conn, hook.getBoundConnection("default"));
        }

        @Test
        @DisplayName("该源无绑定时返回 null（多源并存互不串扰）")
        void unboundSourceReturnsNull() {
            Connection conn = org.mockito.Mockito.mock(Connection.class);
            TransactionBindingHook hook = new StubHook().active(true).bind("order-ds", conn);

            assertSame(conn, hook.getBoundConnection("order-ds"));
            assertNull(hook.getBoundConnection("user-ds"));
        }

        @Test
        @DisplayName("活跃绑定源集合与逐源提取结果一致（Filter 按集合遍历不落空）")
        void idsAndExtractionAreConsistent() {
            Connection order = org.mockito.Mockito.mock(Connection.class);
            Connection user = org.mockito.Mockito.mock(Connection.class);
            TransactionBindingHook hook = new StubHook().active(true)
                    .bind("order-ds", order)
                    .bind("user-ds", user);

            for (String dataSourceId : hook.getActiveBoundDataSourceIds()) {
                assertNotNull(hook.getBoundConnection(dataSourceId));
            }
        }
    }
}
