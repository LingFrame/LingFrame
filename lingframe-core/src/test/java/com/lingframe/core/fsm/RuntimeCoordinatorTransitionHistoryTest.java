package com.lingframe.core.fsm;

import com.lingframe.core.event.EventBus;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * {@link RuntimeCoordinator#getTransitionHistory(String)} 关键语义单测。
 */
@DisplayName("RuntimeCoordinator.getTransitionHistory 转换历史查询单测")
class RuntimeCoordinatorTransitionHistoryTest {

    @DisplayName("转换历史查询语义")
    @Nested
    class TransitionHistorySemantics {

        @DisplayName("注册并迁移后，历史包含转换记录")
        @Test
        void historyContainsTransitionsAfterTransition() {
            EventBus eventBus = mock(EventBus.class);
            RuntimeCoordinator coordinator = new RuntimeCoordinator(eventBus);
            coordinator.start();
            coordinator.register("order-ling");

            coordinator.transition("order-ling", RuntimeStatus.ACTIVE);

            List<TransitionRecord<RuntimeStatus>> history = coordinator.getTransitionHistory("order-ling");

            assertNotNull(history);
            assertTrue(history.size() >= 1, "迁移后应至少有一条转换记录");
            // 最近一条应指向 ACTIVE
            assertEquals(RuntimeStatus.ACTIVE, history.get(history.size() - 1).to());
        }

        @DisplayName("灵元未注册时返回空列表，不抛异常")
        @Test
        void returnsEmptyList_whenLingNotRegistered() {
            RuntimeCoordinator coordinator = new RuntimeCoordinator(mock(EventBus.class));
            coordinator.start();

            List<TransitionRecord<RuntimeStatus>> history = coordinator.getTransitionHistory("unknown-ling");

            assertNotNull(history);
            assertTrue(history.isEmpty());
        }

        @DisplayName("返回的历史不暴露 StateMachine 内部对象")
        @Test
        void historyIsReadOnlyData() {
            EventBus eventBus = mock(EventBus.class);
            RuntimeCoordinator coordinator = new RuntimeCoordinator(eventBus);
            coordinator.start();
            coordinator.register("order-ling");
            coordinator.transition("order-ling", RuntimeStatus.ACTIVE);

            List<TransitionRecord<RuntimeStatus>> history = coordinator.getTransitionHistory("order-ling");

            // 返回的是 List<TransitionRecord>，是纯数据，不是 StateMachine
            for (TransitionRecord<RuntimeStatus> record : history) {
                assertNotNull(record.from());
                assertNotNull(record.to());
                assertTrue(record.timestamp() > 0);
            }
        }
    }
}
