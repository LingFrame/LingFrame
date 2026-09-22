package com.lingframe.core.routing;

import com.lingframe.api.exception.RoutingArchitectureViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 迁移状态机持有者测试
 * <p>
 * 覆盖合法跃迁、非法跃迁、前置独占约束、显式确认 + 排空校验、回滚路径。
 */
@DisplayName("迁移状态机持有者测试")
class MigrationStateHolderTest {

    private MigrationStateHolder holder;

    @BeforeEach
    void setUp() {
        holder = new MigrationStateHolder();
    }

    @Nested
    @DisplayName("初始状态与查询")
    class InitialAndQueryTests {

        @Test
        @DisplayName("未管理的契约默认 CORE_EXCLUSIVE")
        void unmanagedContractDefaultsToCoreExclusive() {
            assertEquals(MigrationPhase.CORE_EXCLUSIVE, holder.getPhase("any-contract"));
        }

        @Test
        @DisplayName("getRecord 未管理契约返回 null")
        void getRecordReturnsNullForUnmanaged() {
            assertEquals(null, holder.getRecord("any-contract"));
        }
    }

    @Nested
    @DisplayName("发起迁移")
    class StartMigrationTests {

        @Test
        @DisplayName("CORE_EXCLUSIVE → MIGRATING 合法跃迁")
        void coreExclusiveToMigrating() {
            holder.startMigration("svc", "lingcore-app", "user-ling");
            assertEquals(MigrationPhase.MIGRATING, holder.getPhase("svc"));
            MigrationStateHolder.PhaseRecord rec = holder.getRecord("svc");
            assertEquals("lingcore-app", rec.getOldCandidate());
            assertEquals("user-ling", rec.getNewCandidate());
        }

        @Test
        @DisplayName("非 CORE_EXCLUSIVE 态发起迁移应抛违例")
        void startMigrationRequiresCoreExclusive() {
            holder.startMigration("svc", "lingcore-app", "user-ling");
            // 当前 MIGRATING，再调 startMigration 应拒
            RoutingArchitectureViolationException ex = assertThrows(RoutingArchitectureViolationException.class,
                    () -> holder.startMigration("svc", "lingcore-app", "user-ling2"));
            assertTrue(ex.getMessage().contains("CORE_EXCLUSIVE"));
        }
    }

    @Nested
    @DisplayName("发起迭代")
    class StartIterationTests {

        @Test
        @DisplayName("LING_EXCLUSIVE → ITERATING 合法跃迁")
        void lingExclusiveToIterating() {
            // 先完整跑一次迁移到 LING_EXCLUSIVE
            holder.startMigration("svc", "lingcore-app", "user-ling");
            holder.confirmPhaseTransition("svc", true);
            assertEquals(MigrationPhase.LING_EXCLUSIVE, holder.getPhase("svc"));

            holder.startIteration("svc", "user-ling", "user-ling:1.1.0");
            assertEquals(MigrationPhase.ITERATING, holder.getPhase("svc"));
        }

        @Test
        @DisplayName("非 LING_EXCLUSIVE 态发起迭代应抛违例")
        void startIterationRequiresLingExclusive() {
            holder.startMigration("svc", "lingcore-app", "user-ling");
            // 当前 MIGRATING，迭代应拒
            RoutingArchitectureViolationException ex = assertThrows(RoutingArchitectureViolationException.class,
                    () -> holder.startIteration("svc", "user-ling", "user-ling:1.1.0"));
            assertTrue(ex.getMessage().contains("LING_EXCLUSIVE"));
        }
    }

    @Nested
    @DisplayName("确认相变")
    class ConfirmPhaseTransitionTests {

        @Test
        @DisplayName("MIGRATING + drainOk=true → LING_EXCLUSIVE")
        void migratingConfirmToLingExclusive() {
            holder.startMigration("svc", "lingcore-app", "user-ling");
            holder.confirmPhaseTransition("svc", true);
            assertEquals(MigrationPhase.LING_EXCLUSIVE, holder.getPhase("svc"));
        }

        @Test
        @DisplayName("ITERATING + drainOk=true → LING_EXCLUSIVE")
        void iteratingConfirmToLingExclusive() {
            holder.startMigration("svc", "lingcore-app", "user-ling");
            holder.confirmPhaseTransition("svc", true);
            holder.startIteration("svc", "user-ling", "user-ling:1.1.0");
            holder.confirmPhaseTransition("svc", true);
            assertEquals(MigrationPhase.LING_EXCLUSIVE, holder.getPhase("svc"));
        }

        @Test
        @DisplayName("排空未通过时应抛违例")
        void confirmRequiresDrainOk() {
            holder.startMigration("svc", "lingcore-app", "user-ling");
            RoutingArchitectureViolationException ex = assertThrows(RoutingArchitectureViolationException.class,
                    () -> holder.confirmPhaseTransition("svc", false));
            assertTrue(ex.getMessage().contains("drain"));
        }

        @Test
        @DisplayName("非二元态确认应抛违例")
        void confirmRequiresBinaryPhase() {
            RoutingArchitectureViolationException ex = assertThrows(RoutingArchitectureViolationException.class,
                    () -> holder.confirmPhaseTransition("svc", true));
            assertTrue(ex.getMessage().contains("binary"));
        }
    }

    @Nested
    @DisplayName("回滚相变")
    class RollbackPhaseTransitionTests {

        @Test
        @DisplayName("MIGRATING 回滚 → CORE_EXCLUSIVE")
        void migratingRollbackToCoreExclusive() {
            holder.startMigration("svc", "lingcore-app", "user-ling");
            holder.rollbackPhaseTransition("svc");
            assertEquals(MigrationPhase.CORE_EXCLUSIVE, holder.getPhase("svc"));
        }

        @Test
        @DisplayName("ITERATING 回滚 → LING_EXCLUSIVE")
        void iteratingRollbackToLingExclusive() {
            holder.startMigration("svc", "lingcore-app", "user-ling");
            holder.confirmPhaseTransition("svc", true);
            holder.startIteration("svc", "user-ling", "user-ling:1.1.0");
            holder.rollbackPhaseTransition("svc");
            assertEquals(MigrationPhase.LING_EXCLUSIVE, holder.getPhase("svc"));
        }

        @Test
        @DisplayName("非二元态回滚应抛违例")
        void rollbackRequiresBinaryPhase() {
            RoutingArchitectureViolationException ex = assertThrows(RoutingArchitectureViolationException.class,
                    () -> holder.rollbackPhaseTransition("svc"));
            assertTrue(ex.getMessage().contains("binary"));
        }
    }

    @Nested
    @DisplayName("启动阶段恢复")
    class RestorePhaseTests {

        @Test
        @DisplayName("二元候选态恢复 MIGRATING 并带候选元数据")
        void restoreBinaryPhaseReconstructsCandidates() {
            holder.restorePhase("svc", MigrationPhase.MIGRATING, "lingcore-app", "user-ling");
            assertEquals(MigrationPhase.MIGRATING, holder.getPhase("svc"));
            MigrationStateHolder.PhaseRecord rec = holder.getRecord("svc");
            assertEquals("lingcore-app", rec.getOldCandidate());
            assertEquals("user-ling", rec.getNewCandidate());
        }

        @Test
        @DisplayName("独占态恢复 LING_EXCLUSIVE 仅需保留方候选")
        void restoreExclusivePhaseKeepsOnlyCandidate() {
            holder.restorePhase("svc", MigrationPhase.LING_EXCLUSIVE, "user-ling", null);
            assertEquals(MigrationPhase.LING_EXCLUSIVE, holder.getPhase("svc"));
            assertEquals("user-ling", holder.getRecord("svc").getOldCandidate());
        }

        @Test
        @DisplayName("二元态缺候选键应抛 IllegalArgumentException")
        void restoreBinaryRequiresBothCandidates() {
            assertThrows(IllegalArgumentException.class,
                    () -> holder.restorePhase("svc", MigrationPhase.MIGRATING, "lingcore-app", null));
        }

        @Test
        @DisplayName("独占态缺保留方应抛 IllegalArgumentException")
        void restoreExclusiveRequiresCandidate() {
            assertThrows(IllegalArgumentException.class,
                    () -> holder.restorePhase("svc", MigrationPhase.LING_EXCLUSIVE, null, null));
        }

        @Test
        @DisplayName("恢复后仍可正常推进后续相变")
        void restoredPhaseCanContinue() {
            holder.restorePhase("svc", MigrationPhase.MIGRATING, "lingcore-app", "user-ling");
            holder.confirmPhaseTransition("svc", true);
            assertEquals(MigrationPhase.LING_EXCLUSIVE, holder.getPhase("svc"));

            holder.startIteration("svc", "user-ling", "user-ling:1.1.0");
            assertEquals(MigrationPhase.ITERATING, holder.getPhase("svc"));
        }
    }

    @Nested
    @DisplayName("卸载清理")
    class EvictTests {

        @Test
        @DisplayName("evict 应清除灵元参与的所有契约迁移记录")
        void evictShouldRemoveRecordsForLing() {
            holder.startMigration("svc-a", "lingcore-app", "user-ling");
            holder.startMigration("svc-b", "lingcore-app", "user-ling");
            assertEquals(MigrationPhase.MIGRATING, holder.getPhase("svc-a"));
            assertEquals(MigrationPhase.MIGRATING, holder.getPhase("svc-b"));

            holder.evict("user-ling");

            // 灵元 user-ling 被卸载，其参与的契约记录应清掉，回到默认 CORE_EXCLUSIVE
            assertEquals(MigrationPhase.CORE_EXCLUSIVE, holder.getPhase("svc-a"));
            assertEquals(MigrationPhase.CORE_EXCLUSIVE, holder.getPhase("svc-b"));
        }

        @Test
        @DisplayName("evict 不应清除无关契约的记录")
        void evictShouldNotAffectUnrelatedRecords() {
            holder.startMigration("svc-a", "lingcore-app", "user-ling");
            holder.startMigration("svc-b", "lingcore-app", "other-ling");

            holder.evict("user-ling");

            assertEquals(MigrationPhase.CORE_EXCLUSIVE, holder.getPhase("svc-a"));
            // other-ling 的记录应保留
            assertEquals(MigrationPhase.MIGRATING, holder.getPhase("svc-b"));
        }
    }

    @Nested
    @DisplayName("MigrationPhase 枚举行为")
    class MigrationPhaseTests {

        @Test
        @DisplayName("isExclusive 仅对独占态返回 true")
        void isExclusiveOnlyForExclusivePhases() {
            assertTrue(MigrationPhase.CORE_EXCLUSIVE.isExclusive());
            assertTrue(MigrationPhase.LING_EXCLUSIVE.isExclusive());
            assertFalse(MigrationPhase.MIGRATING.isExclusive());
            assertFalse(MigrationPhase.ITERATING.isExclusive());
        }

        @Test
        @DisplayName("isBinary 仅对二元候选态返回 true")
        void isBinaryOnlyForBinaryPhases() {
            assertTrue(MigrationPhase.MIGRATING.isBinary());
            assertTrue(MigrationPhase.ITERATING.isBinary());
            assertFalse(MigrationPhase.CORE_EXCLUSIVE.isBinary());
            assertFalse(MigrationPhase.LING_EXCLUSIVE.isBinary());
        }

        @Test
        @DisplayName("canTransitTo 校验合法跃迁")
        void canTransitToValidatesTransitions() {
            assertTrue(MigrationPhase.CORE_EXCLUSIVE.canTransitTo(MigrationPhase.MIGRATING));
            assertTrue(MigrationPhase.MIGRATING.canTransitTo(MigrationPhase.LING_EXCLUSIVE));
            assertTrue(MigrationPhase.MIGRATING.canTransitTo(MigrationPhase.CORE_EXCLUSIVE));
            assertTrue(MigrationPhase.LING_EXCLUSIVE.canTransitTo(MigrationPhase.ITERATING));
            assertTrue(MigrationPhase.ITERATING.canTransitTo(MigrationPhase.LING_EXCLUSIVE));

            assertFalse(MigrationPhase.CORE_EXCLUSIVE.canTransitTo(MigrationPhase.ITERATING));
            assertFalse(MigrationPhase.CORE_EXCLUSIVE.canTransitTo(MigrationPhase.CORE_EXCLUSIVE));
            assertFalse(MigrationPhase.LING_EXCLUSIVE.canTransitTo(MigrationPhase.MIGRATING));
        }
    }
}
