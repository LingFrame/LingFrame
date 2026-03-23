package com.lingframe.core.fsm;

import com.lingframe.api.event.LingEventListener;
import com.lingframe.core.event.EventBus;
import com.lingframe.core.event.InstanceDestroyedEvent;
import com.lingframe.core.event.InstanceStateChangedEvent;
import com.lingframe.core.event.RuntimeStateChangedEvent;
import lombok.extern.slf4j.Slf4j;

import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 运行时状态协调器，也是 {@link RuntimeStatus} 状态机的唯一拥有者。
 * <p>
 * 在当前“双层状态机”模型中：
 * <ul>
 *   <li>实例状态协调器负责写入单实例事实状态</li>
 *   <li>{@code RuntimeCoordinator} 订阅实例事件并维护运行时快照</li>
 *   <li>它再基于快照聚合出宏观 {@link RuntimeStatus}</li>
 * </ul>
 * 因此两层状态机不是“对象互相持有并直接改状态”，
 * 而是“实例层产出事实，运行时层订阅事实并收敛宏观状态”。
 * <p>
 * 通过 {@link EventBus#subscribeGlobal} 以全局监听器身份订阅实例事件，
 * 聚合评估后驱动运行时状态机，实现微观到宏观的自动联动。
 * <p>
 * 生命周期：
 * <ul>
 *   <li>{@link #start()}：注册全局监听器，开始工作</li>
 *   <li>{@link #stop()}：注销监听器，停止工作</li>
 * </ul>
 * <p>
 * 事件链路：
 * <pre>
 * 实例协调器 `InstanceCoordinator`        运行时协调器 `RuntimeCoordinator`
 *   ├─ 驱动 InstanceStatus FSM              ├─ 监听 InstanceStateChangedEvent
 *   ├─ 发布 InstanceStateChangedEvent ───→  ├─ 聚合评估所有实例状态
 *   └─                                     ├─ 驱动 RuntimeStatus FSM
 *                                          └─ 发布 RuntimeStateChangedEvent
 * </pre>
 *
 * <b>线程安全</b>：依赖 ConcurrentHashMap + StateMachine 的 CAS，无显式锁。
 * 并发事件可能触发多次 reevaluate，但 CAS 保证最终一致。
 */
@Slf4j
public class RuntimeCoordinator {

    /**
     * 基于 CAS 的重试上限
     */
    private static final int MAX_RETRIES = 3;

    /**
     * `lingId -> 运行时状态机`
     * 这是运行时宏观状态的正式真源。
     */
    private final ConcurrentMap<String, StateMachine<RuntimeStatus>> machines = new ConcurrentHashMap<>();

    /**
     * `lingId -> { instanceKey -> InstanceStatus }`
     * <p>
     * 维护每个 Ling 下所有活跃实例的状态快照。
     * `instanceKey` 使用实例版本号 `version`，同一灵元的不同版本各占一个条目。
     * 实例进入 DEAD 后从快照中移除。
     * 这是 runtime 聚合时看到的“事实视图”，而不是直接去扫描对象图。
     * 这样可以把“实例状态写入”和“运行时状态聚合”解耦成事件边界。
     */
    private final ConcurrentMap<String, ConcurrentMap<String, InstanceStatus>> snapshots = new ConcurrentHashMap<>();

    /**
     * 聚合评估策略（可插拔）
     */
    private final RuntimeEvaluationPolicy policy;

    /**
     * 事件总线
     */
    private final EventBus eventBus;

    /* ---------- 事件监听器引用（用于注销）---------- */
    private final LingEventListener<InstanceStateChangedEvent> stateChangedListener;
    private final LingEventListener<InstanceDestroyedEvent> destroyedListener;

    public RuntimeCoordinator(EventBus eventBus) {
        this(eventBus, new DefaultRuntimeEvaluationPolicy());
    }

    /**
     * @param eventBus 事件总线，为 null 时不发布事件
     * @param policy   聚合评估策略
     */
    public RuntimeCoordinator(EventBus eventBus, RuntimeEvaluationPolicy policy) {
        this.eventBus = eventBus;
        this.policy = policy;

        // 构造时创建监听器引用，便于 start/stop 对称注册注销
        this.stateChangedListener = this::onInstanceStateChanged;
        this.destroyedListener = this::onInstanceDestroyed;
    }

    /* ==================== 生命周期管理 ==================== */

    /**
     * 启动协调器：注册全局事件监听器
     */
    public void start() {
        log.info("RuntimeCoordinator starting, subscribing to instance events");
        eventBus.subscribeGlobal(InstanceStateChangedEvent.class, stateChangedListener);
        eventBus.subscribeGlobal(InstanceDestroyedEvent.class, destroyedListener);
    }

    /**
     * 停止协调器：注销全局事件监听器
     */
    public void stop() {
        log.info("RuntimeCoordinator stopping, unsubscribing from instance events");
        eventBus.unsubscribeGlobal(InstanceStateChangedEvent.class, stateChangedListener);
        eventBus.unsubscribeGlobal(InstanceDestroyedEvent.class, destroyedListener);
    }

    /* ==================== Ling 注册与注销 ==================== */

    /**
     * 注册一个 Ling，创建对应的运行时状态机。
     * 幂等操作：重复注册返回已有状态机。
     * 这是运行时 FSM 的唯一创建入口。
     *
     * @param lingId Ling 标识
     * @return 该 Ling 的运行时状态机
     */
    public StateMachine<RuntimeStatus> register(String lingId) {
        snapshots.putIfAbsent(lingId, new ConcurrentHashMap<>());
        // `register` 是运行时状态机的唯一创建入口，保证 `LingRuntime` 与协调器
        // 读取的都是同一份宏观状态真源。
        return machines.computeIfAbsent(lingId, id -> {
            log.info("Ling [{}] registered, runtime FSM created with INACTIVE", id);
            return RuntimeStatus.newMachine(id);
        });
    }

    /**
     * 获取指定 Ling 的运行时状态，未注册返回 null
     */
    public RuntimeStatus getStatus(String lingId) {
        StateMachine<RuntimeStatus> fsm = machines.get(lingId);
        return fsm != null ? fsm.current() : null;
    }

    /* ==================== 事件监听入口 ==================== */

    /**
     * 处理实例状态变更事件。
     * <p>
     * 由实例状态协调器发布，本方法更新实例快照后触发聚合评估。
     * 若该 Ling 尚未注册，自动防御性注册。
     */
    public void onInstanceStateChanged(InstanceStateChangedEvent event) {
        String lingId = event.getLingId();
        String version = event.getVersion();
        InstanceStatus to = event.getToStatus();

        // 防御性注册（正常流程应在部署时显式调用 register）
        register(lingId);

        ConcurrentMap<String, InstanceStatus> states = snapshots.get(lingId);

        if (to == InstanceStatus.DEAD) {
            // 终态实例移出快照
            states.remove(version);
            log.debug("Instance [{}/{}] removed from snapshot (DEAD)", lingId, version);
        } else {
            states.put(version, to);
            log.debug("Instance [{}/{}] snapshot updated to {}", lingId, version, to);
        }

        // 触发聚合评估：实例层只汇报事实，运行时层自己决定宏观状态。
        reevaluate(lingId);
    }

    /**
     * 处理实例销毁事件（兜底清理）。
     * <p>
     * 与 {@link #onInstanceStateChanged} 中的 DEAD 处理互为幂等保障，
     * 防止极端时序下快照残留。
     */
    public void onInstanceDestroyed(InstanceDestroyedEvent event) {
        String lingId = event.getLingId();
        String version = event.getVersion();

        ConcurrentMap<String, InstanceStatus> states = snapshots.get(lingId);
        if (states != null) {
            states.remove(version);
        }

        reevaluate(lingId);
    }

    /* ==================== 主动运维操作 ==================== */

    /**
     * 主动关闭一个 Ling 的运行时（管理员/运维触发）。
     * <p>
     * `STOPPING` 在当前版本中属于“运维意图态”。
     * 一旦进入 STOPPING，就不再允许实例层事实把它重新拉回 ACTIVE / DEGRADED。
     * 当所有实例都 DEAD 后，由 {@link #reevaluate} 自动跃迁到 REMOVED。
     */
    public void shutdown(String lingId) {
        StateMachine<RuntimeStatus> fsm = machines.get(lingId);
        if (fsm == null) {
            log.warn("Cannot shutdown unknown ling [{}]", lingId);
            return;
        }

        TransitionResult<RuntimeStatus> result = fsm.transition(RuntimeStatus.STOPPING);
        if (result.isSuccess() && result.from() != result.target()) {
            log.info("Ling [{}] runtime shutting down: {} -> {}", lingId, result.from(), result.target());
            publishChanged(lingId, result);
        } else if (result.isIllegal()) {
            log.warn("Cannot shutdown ling [{}] from state {}", lingId, result.from());
        }
    }

    /**
     * 主动触发运行时状态转换（Dashboard/运维调用）。
     * <p>
     * 此方法是外部触发状态转换的唯一入口，确保所有状态变更都通过 RuntimeCoordinator，
     * 从而保证事件正确发布。
     *
     * @param lingId Ling 标识
     * @param target 目标状态
     * @return 转换结果
     */
    public TransitionResult<RuntimeStatus> transition(String lingId, RuntimeStatus target) {
        StateMachine<RuntimeStatus> fsm = machines.get(lingId);
        if (fsm == null) {
            log.warn("Cannot transition unknown ling [{}]", lingId);
            return TransitionResult.illegal(null, target);
        }

        TransitionResult<RuntimeStatus> result = fsm.transition(target);
        if (result.isSuccess() && result.from() != result.target()) {
            log.info("Ling [{}] runtime state changed: {} -> {}", lingId, result.from(), result.target());
            publishChanged(lingId, result);
        } else if (result.isIllegal()) {
            log.warn("Illegal transition for ling [{}]: {} -> {}", lingId, result.from(), target);
        }
        return result;
    }

    /**
     * 清理已移除 Ling 的内存数据（可选，由大管家定期调用）
     */
    public void purge(String lingId) {
        StateMachine<RuntimeStatus> fsm = machines.get(lingId);
        if (fsm != null && fsm.current() == RuntimeStatus.REMOVED) {
            machines.remove(lingId);
            snapshots.remove(lingId);
            log.info("Ling [{}] purged from RuntimeCoordinator", lingId);
        }
    }

    /* ==================== 聚合评估核心 ==================== */

    /**
     * 重新评估指定 Ling 的运行时状态。
     * <p>
     * 读取实例快照 -> 调用策略评估 -> CAS 驱动状态机。
     * 并发安全依赖 CAS 重试 + 事件驱动的最终一致性：
     * 即使本次 CAS 失败，下一个事件也会再次触发评估并收敛到正确状态。
     */
    private void reevaluate(String lingId) {
        StateMachine<RuntimeStatus> fsm = machines.get(lingId);
        if (fsm == null) {
            return;
        }

        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            RuntimeStatus current = fsm.current();

            // `STOPPING` 是运维意图态。
            // 一旦进入 STOPPING，runtime 不允许再被“实例变好了”拉回 ACTIVE / DEGRADED，
            // 只允许在实例全部消失后继续前进到 REMOVED。
            if (current == RuntimeStatus.STOPPING) {
                tryFinishShutdown(lingId, fsm);
                return;
            }

            // 终态不再评估
            if (current == RuntimeStatus.REMOVED) {
                return;
            }

            // 收集实例状态快照，而不是直接操作 LingInstance：
            // 这样双层状态机是“事件联动”，不是“对象互写”。
            ConcurrentMap<String, InstanceStatus> states = snapshots.get(lingId);
            Collection<InstanceStatus> stateValues = (states != null)
                    ? states.values()
                    : java.util.Collections.emptyList();

            // 调用策略评估
            RuntimeStatus suggested = policy.evaluate(current, stateValues);

            // 评估结果与当前相同，无需跃迁
            if (suggested == current) {
                return;
            }

            // 通过 CAS 驱动状态跃迁
            TransitionResult<RuntimeStatus> result = fsm.transition(suggested);
            switch (result.code()) {
                case SUCCESS:
                    log.info("Ling [{}] runtime state changed: {} -> {} (instances: {})",
                            lingId, result.from(), result.target(), stateValues);
                    publishChanged(lingId, result);
                    return;

                case ILLEGAL:
                    // 策略建议的跃迁在转换表中不合法，记录告警并放弃
                    log.warn("Illegal runtime transition for [{}]: {} -> {}, policy suggested "
                                    + "an unreachable state, skipping",
                            lingId, result.from(), result.target());
                    return;

                case CONFLICT:
                    // 另一线程抢先修改了状态，重新读取快照后重试
                    log.debug("CAS conflict during reevaluation of [{}], retry {}/{}",
                            lingId, attempt + 1, MAX_RETRIES);
                    break;

                default:
                    throw new AssertionError("Unknown transition code: " + result.code());
            }
        }

        // 重试耗尽不抛异常：依赖下一个事件再次触发评估
        log.warn("Runtime reevaluation for [{}] exhausted {} retries, "
                + "will converge on next event", lingId, MAX_RETRIES);
    }

    /**
     * 在 `STOPPING` 状态下，检查是否所有实例都已销毁，若是则跃迁到 `REMOVED`
     */
    private void tryFinishShutdown(String lingId, StateMachine<RuntimeStatus> fsm) {
        ConcurrentMap<String, InstanceStatus> states = snapshots.get(lingId);
        boolean allGone = (states == null || states.isEmpty());

        if (allGone) {
            TransitionResult<RuntimeStatus> result = fsm.transition(RuntimeStatus.REMOVED);
            if (result.isSuccess()) {
                log.info("Ling [{}] all instances destroyed, runtime -> REMOVED", lingId);
                publishChanged(lingId, result);
            }
        }
    }

    /* ==================== 事件发布（单一出口）==================== */

    private void publishChanged(String lingId, TransitionResult<RuntimeStatus> result) {
        if (eventBus == null || result.from() == result.target()) {
            return;
        }
        eventBus.publish(new RuntimeStateChangedEvent(lingId, result.from(), result.target()));
    }
}
