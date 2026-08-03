package com.lingframe.core.routing;

import com.lingframe.api.exception.RoutingArchitectureViolationException;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 迁移状态机持有者。
 * <p>
 * 管理功能（契约）粒度的 {@link MigrationPhase} 转换，
 * 校验相变前置条件（独占态才允许发起新迁移/迭代；候选数不超过 2），
 * 提供 {@code startMigration / confirmPhaseTransition / rollbackPhaseTransition} API。
 * <p>
 * 与路由层同包，表达「迁移阶段是路由层的元状态」。
 * 路由层（{@link ProviderWeightRouter}）本身不读此状态，
 * 但编排层（{@code DefaultLingLifecycleEngine}）与控制面（Dashboard）通过本类读写。
 * <p>
 * 持久化：phase 与候选元数据由 Dashboard 侧 {@code GovernanceStorage}
 * 以 {@code config_type = 'migration'} 落盘；本类不直接耦合存储实现，
 * 通过 {@link MigrationStateStore} 接口注入。
 * <p>
 * <b>迁移无自动推进</b>：本类不监听任何权重变更事件（历史上 {@link com.lingframe.core.event.ProviderWeightChangedEvent}
 * 曾宣称由本类监听，实际从未订阅，属死广播，已删除发布与伪声明）。
 * 阶段推进仅由显式编排驱动：外部先调 {@code startMigration}，再在权重调整 + 排空校验（drainOk）
 * 通过后调用 {@code confirmPhaseTransition}（或失败时 {@code rollbackPhaseTransition}）。
 *
 * @author lingframe
 */
@Slf4j
public class MigrationStateHolder {

    /** 契约 ID → 当前迁移阶段 */
    private final Map<String, PhaseRecord> phases = new ConcurrentHashMap<>();

    /** 持久化接口（可选，由 Dashboard 侧注入；core 单元测试时为 null） */
    private final MigrationStateStore store;

    public MigrationStateHolder() {
        this(null);
    }

    public MigrationStateHolder(MigrationStateStore store) {
        this.store = store;
    }

    /**
     * 查询指定契约的当前迁移阶段。
     *
     * @param contractId 契约 ID
     * @return 当前阶段；未在管理中返回 {@link MigrationPhase#CORE_EXCLUSIVE}（默认灵核独占）
     */
    public MigrationPhase getPhase(String contractId) {
        PhaseRecord rec = phases.get(contractId);
        return rec == null ? MigrationPhase.CORE_EXCLUSIVE : rec.phase;
    }

    /**
     * 查询指定契约的当前候选元数据。
     *
     * @param contractId 契约 ID
     * @return 候选元数据；未在管理中返回 null
     */
    public PhaseRecord getRecord(String contractId) {
        return phases.get(contractId);
    }

    /**
     * 发起迁移：灵核独占 → 灵元接管。
     * <p>
     * 前置条件：当前阶段为 {@link MigrationPhase#CORE_EXCLUSIVE}。
     *
     * @param contractId     契约 ID
     * @param oldCandidate   灵核 provider 键（如 {@code lingcore-app})
     * @param newCandidate   灵元 provider 键（裸 {@code lingId})
     * @throws RoutingArchitectureViolationException 非独占态发起迁移
     */
    public synchronized void startMigration(String contractId, String oldCandidate, String newCandidate) {
        Objects.requireNonNull(contractId, "contractId");
        Objects.requireNonNull(oldCandidate, "oldCandidate");
        Objects.requireNonNull(newCandidate, "newCandidate");

        PhaseRecord rec = phases.get(contractId);
        MigrationPhase current = rec == null ? MigrationPhase.CORE_EXCLUSIVE : rec.phase;
        if (current != MigrationPhase.CORE_EXCLUSIVE) {
            throw new RoutingArchitectureViolationException(
                    "startMigration requires CORE_EXCLUSIVE phase, contract=" + contractId
                            + " current=" + current);
        }
        PhaseRecord next = new PhaseRecord(MigrationPhase.MIGRATING, oldCandidate, newCandidate);
        persist(contractId, next);
        phases.put(contractId, next);
        log.info("Migration started: contract={} core={} ling={}", contractId, oldCandidate, newCandidate);
    }

    /**
     * 发起迭代：灵元独占 → 灵元新版本接管。
     * <p>
     * 前置条件：当前阶段为 {@link MigrationPhase#LING_EXCLUSIVE}。
     *
     * @param contractId     契约 ID
     * @param oldCandidate   旧灵元 provider 键（裸 {@code lingId}）
     * @param newCandidate   新灵元 provider 键（{@code lingId:version}）
     * @throws RoutingArchitectureViolationException 非灵元独占态发起迭代
     */
    public synchronized void startIteration(String contractId, String oldCandidate, String newCandidate) {
        Objects.requireNonNull(contractId, "contractId");
        Objects.requireNonNull(oldCandidate, "oldCandidate");
        Objects.requireNonNull(newCandidate, "newCandidate");

        PhaseRecord rec = phases.get(contractId);
        MigrationPhase current = rec == null ? MigrationPhase.CORE_EXCLUSIVE : rec.phase;
        if (current != MigrationPhase.LING_EXCLUSIVE) {
            throw new RoutingArchitectureViolationException(
                    "startIteration requires LING_EXCLUSIVE phase, contract=" + contractId
                            + " current=" + current);
        }
        PhaseRecord next = new PhaseRecord(MigrationPhase.ITERATING, oldCandidate, newCandidate);
        persist(contractId, next);
        phases.put(contractId, next);
        log.info("Iteration started: contract={} old={} new={}", contractId, oldCandidate, newCandidate);
    }

    /**
     * 显式确认相变完成。
     * <p>
     * 前置条件：
     * <ul>
     *   <li>当前阶段为二元候选态（MIGRATING / ITERATING）</li>
     *   <li>退出方候选的活跃请求数为 0（排空校验）——由 caller 通过 {@code drainOk} 参数传入</li>
     * </ul>
     *
     * @param contractId 契约 ID
     * @param drainOk    排空校验结果（退出方 activeRequests == 0）
     * @throws RoutingArchitectureViolationException 非二元态确认或排空未通过
     */
    public synchronized void confirmPhaseTransition(String contractId, boolean drainOk) {
        PhaseRecord rec = phases.get(contractId);
        if (rec == null || !rec.phase.isBinary()) {
            throw new RoutingArchitectureViolationException(
                    "confirmPhaseTransition requires binary phase, contract=" + contractId);
        }
        if (!drainOk) {
            throw new RoutingArchitectureViolationException(
                    "confirmPhaseTransition requires drain (activeRequests==0) for exiting candidate, contract="
                            + contractId);
        }
        // 相变确认后保留方候选独占——MIGRATING 命中保留方为灵元,ITERATING 命中保留方为新灵元版本,
        // 两者均进入 LING_EXCLUSIVE 态；三元两分支返回同值是冗余,直接赋值消除歧义
        MigrationPhase next = MigrationPhase.LING_EXCLUSIVE;
        PhaseRecord nextRec = new PhaseRecord(next, rec.newCandidate, null);
        persist(contractId, nextRec);
        phases.put(contractId, nextRec);
        log.info("Phase transition confirmed: contract={} → {}", contractId, next);
    }

    /**
     * 显式回滚相变。
     * <p>
     * 据退出的是 old/new candidate 判定前进或后退：
     * <ul>
     *   <li>MIGRATING 回滚 → CORE_EXCLUSIVE（退回灵核）</li>
     *   <li>ITERATING 回滚 → LING_EXCLUSIVE（退回旧灵元）</li>
     * </ul>
     *
     * @param contractId 契约 ID
     * @throws RoutingArchitectureViolationException 非二元态回滚
     */
    public synchronized void rollbackPhaseTransition(String contractId) {
        PhaseRecord rec = phases.get(contractId);
        if (rec == null || !rec.phase.isBinary()) {
            throw new RoutingArchitectureViolationException(
                    "rollbackPhaseTransition requires binary phase, contract=" + contractId);
        }
        MigrationPhase next = rec.phase == MigrationPhase.MIGRATING
                ? MigrationPhase.CORE_EXCLUSIVE
                : MigrationPhase.LING_EXCLUSIVE;
        // 回滚后保留方为退出方候选的反面——MIGRATING 命中保留灵核(oldCandidate),
        // ITERATING 命中保留旧灵元(oldCandidate)；三元两分支返回同值是冗余,直接赋值消除歧义
        String keepCandidate = rec.oldCandidate;
        PhaseRecord nextRec = new PhaseRecord(next, keepCandidate, null);
        persist(contractId, nextRec);
        phases.put(contractId, nextRec);
        log.info("Phase transition rolled back: contract={} → {} keep={}", contractId, next, keepCandidate);
    }

    /**
     * 启动恢复专用：按持久化记录重建指定契约的迁移阶段。
     * <p>
     * 仅由 Dashboard 侧 {@code GovernanceConfigRestorer} 在启动恢复时调用；
     * 不走前置阶段校验（重启后内存态为空，无「当前阶段」可比对），
     * 也不触发持久化回写（数据来源即持久化记录）。
     * <p>
     * 校验规则：
     * <ul>
     *   <li>{@code contractId} / {@code phase} 非空</li>
     *   <li>二元候选态（MIGRATING / ITERATING）必须提供双候选键；独占态仅需保留方候选键</li>
     * </ul>
     *
     * @param contractId   契约 ID
     * @param phase        恢复的目标阶段
     * @param oldCandidate 退出方候选键（独占态下为保留方候选键）
     * @param newCandidate 进入方候选键（独占态下可为 null）
     * @throws IllegalArgumentException 参数不合法
     */
    public synchronized void restorePhase(String contractId, MigrationPhase phase,
                                          String oldCandidate, String newCandidate) {
        Objects.requireNonNull(contractId, "contractId");
        Objects.requireNonNull(phase, "phase");
        if (phase.isBinary() && (oldCandidate == null || newCandidate == null)) {
            throw new IllegalArgumentException(
                    "restorePhase requires both candidates for binary phase, contract=" + contractId
                            + " phase=" + phase);
        }
        if (!phase.isBinary() && oldCandidate == null) {
            throw new IllegalArgumentException(
                    "restorePhase requires oldCandidate for exclusive phase, contract=" + contractId
                            + " phase=" + phase);
        }
        phases.put(contractId, new PhaseRecord(phase, oldCandidate, newCandidate));
        log.info("Migration phase restored: contract={} phase={} old={} new={}",
                contractId, phase, oldCandidate, newCandidate);
    }

    /**
     * 卸载清理：灵元被卸载时，清掉其参与的所有契约迁移记录。
     * <p>
     * 由 {@code DashboardLingOperations.uninstallLing} 调用，
     * 替代旧的 {@code canaryRouter.removeCanaryConfig(lingId)}。
     *
     * @param lingId 被卸载灵元 ID
     */
    public synchronized void evict(String lingId) {
        // 先持久化删除、成功后才内存移除：避免 store.delete 抛异常时出现
        // 「磁盘已部分删除 / 内存仍存」的相反方向状态分裂
        List<String> contractsToEvict = new ArrayList<>();
        for (Map.Entry<String, PhaseRecord> e : phases.entrySet()) {
            PhaseRecord rec = e.getValue();
            // 培配候选键:优先按迭代期 lingId:version 命中,其次按迁移期裸 lingId 命中
            // 不用 startsWith 避免前缀碰撞(user-ling 命中卸载会误删 user-ling-v2 命中无关记录)
            boolean involved = matchesCandidate(rec.oldCandidate, lingId)
                    || matchesCandidate(rec.newCandidate, lingId);
            if (involved) {
                if (store != null) {
                    store.delete(e.getKey());
                }
                contractsToEvict.add(e.getKey());
                log.info("Evicting migration record for ling={} contract={}", lingId, e.getKey());
            }
        }
        for (String contractId : contractsToEvict) {
            phases.remove(contractId);
        }
    }

    private void persist(String contractId, PhaseRecord rec) {
        if (store != null) {
            store.save(contractId, rec);
        }
    }

    /**
     * 培配候选键是否属于指定灵元。
     * <p>
     * 培配键可能命中裸 lingId（迁移期灵元）或 lingId:version（迭代期灵元版本）;
     * 命中 lingcore-app 命中灵核时不培配（灵核卸载不调本方法）。
     * <p>
     * 不用 {@code String.startsWith} 避免前缀碰撞:
     * 例如 {@code user-ling} 命中卸载时 {@code startsWith} 会误删 {@code user-ling-v2}
     * 命中无关迁移记录。
     *
     * @param candidateKey 命中候选键（{@link PhaseRecord#getOldCandidate} / {@link PhaseRecord#getNewCandidate})
     * @param lingId      命中卸载灵元 ID
     * @return true 命中候选属于该灵元
     */
    private boolean matchesCandidate(String candidateKey, String lingId) {
        if (candidateKey == null || lingId == null) {
            return false;
        }
        // 命中裸 lingId 命中或 lingId:version 命中前缀（命中 ':' 命中分隔符后才安全）
        return candidateKey.equals(lingId)
                || candidateKey.startsWith(lingId + ":");
    }

    /**
     * 迁移阶段记录。
     */
    @Getter
    public static class PhaseRecord {
        private final MigrationPhase phase;
        /** 退出方候选键（MIGRATING 时为灵核；ITERATING 时为旧灵元） */
        private final String oldCandidate;
        /** 进入方候选键（MIGRATING 时为灵元；ITERATING 时为新灵元版本） */
        private final String newCandidate;

        public PhaseRecord(MigrationPhase phase, String oldCandidate, String newCandidate) {
            this.phase = phase;
            this.oldCandidate = oldCandidate;
            this.newCandidate = newCandidate;
        }
    }

    /**
     * 持久化接口（由 Dashboard 侧 {@code GovernanceStorage} 实现）。
     */
    public interface MigrationStateStore {
        void save(String contractId, PhaseRecord record);

        void delete(String contractId);
    }
}
