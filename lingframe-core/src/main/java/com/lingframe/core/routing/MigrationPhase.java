package com.lingframe.core.routing;

/**
 * 迁移/演化状态机枚举。
 * <p>
 * 表达功能（契约）从灵核向灵元迁移、灵元版本迭代的宏观阶段。
 * 路由层不感知此状态，它由 {@link MigrationStateHolder} 维护，
 * 供编排层（{@code DefaultLingLifecycleEngine}）与控制面（Dashboard）读写。
 * <p>
 * 状态迁移图（合法跃迁）：
 * <pre>
 *   CORE_EXCLUSIVE ──startMigration──→ MIGRATING
 *   MIGRATING      ──confirmPhase───→ LING_EXCLUSIVE
 *   MIGRATING      ──rollback──────→ CORE_EXCLUSIVE
 *   LING_EXCLUSIVE ──startIteration─→ ITERATING
 *   ITERATING      ──confirmPhase───→ LING_EXCLUSIVE
 *   ITERATING      ──rollback──────→ LING_EXCLUSIVE
 * </pre>
 * <p>
 * 进入下一个状态的前置条件：当前状态必须是「某一方独占（另一方流量为零）」，
 * 否则不允许发起新的迁移或迭代——这一约束把「禁止叠加」从规范升级为系统能力。
 *
 * @author lingframe
 */
public enum MigrationPhase {

    /** 灵核独占：功能尚未迁出，灵核 provider 独占流量 */
    CORE_EXCLUSIVE,

    /** 迁移中：灵核 ↔ 灵元v1，二元权重路由 */
    MIGRATING,

    /** 灵元独占：迁移完成，灵元 v1 独占流量 */
    LING_EXCLUSIVE,

    /** 迭代中：灵元v1 ↔ 灵元v2，二元权重路由 */
    ITERATING;

    /**
     * 判定当前阶段是否为「独占态」——独占态才允许发起新的迁移或迭代。
     */
    public boolean isExclusive() {
        return this == CORE_EXCLUSIVE || this == LING_EXCLUSIVE;
    }

    /**
     * 判定当前阶段是否为「二元候选态」——迁移或迭代进行中。
     */
    public boolean isBinary() {
        return this == MIGRATING || this == ITERATING;
    }

    /**
     * 合法跃迁校验。
     *
     * @param target 目标阶段
     * @return true 表示跃迁合法
     */
    public boolean canTransitTo(MigrationPhase target) {
        if (this == target) {
            return false;
        }
        switch (this) {
            case CORE_EXCLUSIVE:
                return target == MIGRATING;
            case MIGRATING:
                return target == LING_EXCLUSIVE || target == CORE_EXCLUSIVE;
            case LING_EXCLUSIVE:
                return target == ITERATING;
            case ITERATING:
                return target == LING_EXCLUSIVE;
            default:
                return false;
        }
    }
}
