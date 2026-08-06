package com.lingframe.core.routing;

import com.lingframe.api.exception.RoutingArchitectureViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * MigrationStateHolder 生产物理边界阻断测试。
 * 覆盖：在途请求未排空时确认相变拒绝阻断、非法阶段回滚阻断、未注册契约攻击阻断。
 */
@DisplayName("MigrationStateHolder 生产物理边界阻断测试")
class MigrationStateHolderEdgeCasesTest {

    private MigrationStateHolder stateHolder;

    @BeforeEach
    void setUp() {
        stateHolder = new MigrationStateHolder();
    }

    @Test
    @DisplayName("物理阻断 1：在途请求 activeRequests > 0 (drainOk = false) 时确认相变，强制抛出违例阻断下线，防 ClassLoader 泄露引发 OOM")
    void confirmPhaseTransitionFailsWhenDrainNotOk() {
        String contractId = "com.example.OrderService";
        // 发起迁移：CORE_EXCLUSIVE -> MIGRATING (需要 3 个参数: contractId, oldCandidate, newCandidate)
        stateHolder.startMigration(contractId, "lingcore-app", "ling-order-v1");
        assertEquals(MigrationPhase.MIGRATING, stateHolder.getPhase(contractId));

        // 当在途请求未排空（drainOk = false）时强制调 confirm
        RoutingArchitectureViolationException ex = assertThrows(
                RoutingArchitectureViolationException.class,
                () -> stateHolder.confirmPhaseTransition(contractId, false),
                "在途请求未排空时必须抛出架构违例异常强行阻断相变确认"
        );

        // 状态必须保持在 MIGRATING，绝对不允许跃迁到 LING_EXCLUSIVE
        assertEquals(MigrationPhase.MIGRATING, stateHolder.getPhase(contractId));
    }

    @Test
    @DisplayName("物理阻断 2：非二元相变过渡期 (如 LING_EXCLUSIVE 独占态) 发起 confirm 或 rollback，抛出违例阻断")
    void confirmOrRollbackFailsInExclusivePhase() {
        String contractId = "com.example.UserService";
        // 初始处于 CORE_EXCLUSIVE (独占态，非二元相变期)
        assertEquals(MigrationPhase.CORE_EXCLUSIVE, stateHolder.getPhase(contractId));

        // 独占态调 confirmPhaseTransition
        assertThrows(
                RoutingArchitectureViolationException.class,
                () -> stateHolder.confirmPhaseTransition(contractId, true),
                "独占态调用 confirmPhaseTransition 必须抛出违例"
        );

        // 独占态调 rollbackPhaseTransition
        assertThrows(
                RoutingArchitectureViolationException.class,
                () -> stateHolder.rollbackPhaseTransition(contractId),
                "独占态调用 rollbackPhaseTransition 必须抛出违例"
        );
    }

    @Test
    @DisplayName("物理阻断 3：针对不存在/未注册的伪造契约直接调用 confirm 或 rollback，抛出违例阻断")
    void nonExistentContractPhaseOperationsFail() {
        String invalidContract = "com.fake.UnknownService";

        assertThrows(
                RoutingArchitectureViolationException.class,
                () -> stateHolder.confirmPhaseTransition(invalidContract, true)
        );

        assertThrows(
                RoutingArchitectureViolationException.class,
                () -> stateHolder.rollbackPhaseTransition(invalidContract)
        );
    }
}
