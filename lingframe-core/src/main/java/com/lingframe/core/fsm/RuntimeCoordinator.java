package com.lingframe.core.fsm;

import com.lingframe.api.constant.LingCoreConstants;
import com.lingframe.api.event.LingEventListener;
import com.lingframe.core.event.EventBus;
import com.lingframe.core.event.InstanceDestroyedEvent;
import com.lingframe.core.event.RuntimeStateChangedEvent;
import com.lingframe.core.event.InstanceStateChangedEvent;
import com.lingframe.core.fsm.RuntimeStatus.Kind;
import com.lingframe.core.util.NamedThreadFactory;
import lombok.extern.slf4j.Slf4j;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 运行时状态协调器，也是 {@link RuntimeStatus} 状态机的唯一拥有者。
 * <p>
 * 在当前“双层状态机”模型中：
 * <ul>
 *   <li>实例状态协调器负责写入单实例事实状态</li>
 *   <li>{@code RuntimeCoordinator} 订阅实例事件并维护运行时快照</li>
 *   <li>它再基于快照聚合出宏观 {@link RuntimeStatus}</li>
 * </ul>
 * 这种事件联动机制中存在**写优先级锁死设计**（解决 P2-1）：
 * 如果运维主动将状态转换为 {@link RuntimeStatus#STOPPING}（下线意图），
 * 任何来自实例健康状态好转的重新评估（如实例从 ERROR 恢复为 READY）、或者定时的 DEGRADED 健康检查，
 * 甚至外部强制 {@code transition(DEGRADED/ACTIVE)}，都会因为 FSM 规则物理拒绝而被压制。
 * STOPPING 只允许单向进入 REMOVED。这保证了控制面的下线意图具有绝对最高优先级。
 * <p>
 * 换句话说：
 * 实例层只负责“陈述事实”，运行时状态机基于事实聚合，并由 {@link RuntimeStatus#TRANSITIONS}
 * 的合法性矩阵保证最终决议不会被底层的临时抖动所颠覆。
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
     * DEGRADED 灵元健康检查间隔（秒）
     */
    private static final long HEALTH_CHECK_INTERVAL_SECONDS = 30;

    /**
     * `lingId -> 运行时状态机`
     * 这是运行时宏观状态的正式真源。
     */
    private final ConcurrentMap<String, StateMachine<RuntimeStatus>> machines = new ConcurrentHashMap<>();

    /**
     * `lingId -> { instanceId -> InstanceStatus }`
     * <p>
     * 维护每个 Ling 下所有活跃实例的状态快照。
     * 快照键必须是 {@link InstanceStateChangedEvent#getInstanceId()}（进程内实例唯一身份），
     * 不得使用 version：同版本双实例（reload / allowSameVersion）会互相覆盖或 DEAD 抹掉存活实例。
     * 实例进入 DEAD 后从快照中移除。
     * 这是 runtime 聚合时看到的“事实视图”，而不是直接去扫描对象图。
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

    /**
     * DEGRADED 灵元定时健康检查调度器。
     * <p>
     * 当灵元因熔断打开进入 DEGRADED 后，如果没有新请求经过 Pipeline，
     * {@code tryRecoverFromDegraded} 不会被触发，DEGRADED 将持续。
     * 此调度器周期性扫描所有 DEGRADED 灵元并触发 reevaluate，
     * 作为事件驱动路径的兜底保障。
     */
    private volatile ScheduledExecutorService healthCheckExecutor;

    public RuntimeCoordinator(EventBus eventBus) {
        this(eventBus, new DefaultRuntimeEvaluationPolicy());
    }

    /**
     * @param eventBus 事件总线，不允许为 null
     * @param policy   聚合评估策略
     */
    public RuntimeCoordinator(EventBus eventBus, RuntimeEvaluationPolicy policy) {
        this.eventBus = Objects.requireNonNull(eventBus, "EventBus must not be null");
        this.policy = policy;

        // 构造时创建监听器引用，便于 start/stop 对称注册注销
        this.stateChangedListener = this::onInstanceStateChanged;
        this.destroyedListener = this::onInstanceDestroyed;
    }

    /* ==================== 生命周期管理 ==================== */

    /**
     * 启动协调器：注册全局事件监听器 + 启动 DEGRADED 健康检查
     */
    public void start() {
        log.info("RuntimeCoordinator starting, subscribing to instance events");
        eventBus.subscribeGlobal(InstanceStateChangedEvent.class, stateChangedListener);
        eventBus.subscribeGlobal(InstanceDestroyedEvent.class, destroyedListener);
        startHealthCheck();
    }

    /**
     * 停止协调器：注销全局事件监听器 + 停止健康检查
     */
    public void stop() {
        log.info("RuntimeCoordinator stopping, unsubscribing from instance events");
        eventBus.unsubscribeGlobal(InstanceStateChangedEvent.class, stateChangedListener);
        eventBus.unsubscribeGlobal(InstanceDestroyedEvent.class, destroyedListener);
        stopHealthCheck();
    }

    /* ==================== Ling 注册与注销 ==================== */

    /**
     * 注册一个 Ling，创建对应的运行时状态机。
     * 幂等操作：重复注册返回已有状态机。
     * 这是运行时 FSM 的唯一创建入口。
     * <p>
     * 灵核不注册到 RuntimeCoordinator——
     * 由 {@code LingCoreRoutableTarget} 直接暴露 {@code currentStatus()=ACTIVE}，
     * 不进状态机。{@code shutdown}/{@code transition} 调到灵核时
     * {@code fsm == null} 直接拒绝。
     *
     * @param lingId Ling 标识
     * @return 该 Ling 的运行时状态机
     */
    public StateMachine<RuntimeStatus> register(String lingId) {
        if (isLingCoreId(lingId)) {
            throw new IllegalArgumentException(
                    "Ling core id [lingcore-app] must not register into RuntimeCoordinator; "
                            + "use LingCoreRoutableTarget only");
        }
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

    /**
     * 查询灵元运行时状态转换历史。
     * <p>
     * 替代外围模块直接获取 StateMachine 的越界路径，
     * 让 dashboard 等展示层只拿到转换记录列表，不触碰状态机内部对象。
     *
     * @param lingId 灵元 ID
     * @return 转换记录列表（时序快照）；灵元未注册或无记录时返回空列表
     */
    public List<TransitionRecord<RuntimeStatus>> getTransitionHistory(String lingId) {
        StateMachine<RuntimeStatus> machine = machines.get(lingId);
        if (machine == null) {
            return Collections.emptyList();
        }
        List<TransitionRecord<RuntimeStatus>> history = machine.history();
        return history == null ? Collections.emptyList() : history;
    }

    /* ==================== 事件监听入口 ==================== */

    /**
     * 处理实例状态变更事件。
     * <p>
     * 由实例状态协调器发布，本方法更新实例快照后触发聚合评估。
     * 未 {@link #register} 的 ling 事件会被忽略（避免 unregister 后迟到事件复活 FSM）。
     * 编排层须在实例事件出现前调用 {@link #register}（见 DefaultLingLifecycleEngine.ensureRuntimeForDeployment）。
     */
    public void onInstanceStateChanged(InstanceStateChangedEvent event) {
        String lingId = event.getLingId();
        String instanceId = event.getInstanceId();
        InstanceStatus to = event.getToStatus();

        // 灵核实例（lingcore-app）不得进 RuntimeCoordinator：只走 LingCoreRoutableTarget
        if (isLingCoreId(lingId)) {
            log.debug("Ignore instance state event for ling core id [{}]", lingId);
            return;
        }

        // 已注册灵元：更新快照。未注册：不防御性创建 FSM（避免 unregister 后迟到事件造 ghost）
        ConcurrentMap<String, InstanceStatus> states = snapshots.get(lingId);
        if (states == null) {
            log.debug("Ling [{}] not registered or already unregistered, ignore instance state change", lingId);
            return;
        }

        if (to == InstanceStatus.DEAD) {
            // 终态实例移出快照（按 instanceId，不影响同 version 的其他实例）
            states.remove(instanceId);
            log.debug("Instance [{}/{}] removed from snapshot (DEAD, version={})",
                    lingId, instanceId, event.getVersion());
        } else {
            states.put(instanceId, to);
            log.debug("Instance [{}/{}] snapshot updated to {} (version={})",
                    lingId, instanceId, to, event.getVersion());
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
        String instanceId = event.getInstanceId();

        if (isLingCoreId(lingId)) {
            return;
        }

        ConcurrentMap<String, InstanceStatus> states = snapshots.get(lingId);
        if (states == null) {
            // 未注册 / 已 unregister：忽略，禁止通过销毁事件复活 FSM
            return;
        }
        states.remove(instanceId);
        reevaluate(lingId);
    }

    private static boolean isLingCoreId(String lingId) {
        return LingCoreConstants.LINGCORE_LING_ID.equals(lingId);
    }

    /* ==================== 主动运维操作 ==================== */

    /**
     * 主动关闭一个 Ling 的运行时（管理员/运维触发）。
     * <p>
     * `STOPPING` 在当前版本中属于“运维意图态”。
     * 一旦进入 STOPPING，就不再允许实例层事实把它重新拉回 ACTIVE / DEGRADED / RECOVERING。
     * 当所有实例都 DEAD 后，由 {@link #reevaluate} 自动跃迁到 REMOVED。
     */
    public void shutdown(String lingId) {
        // 灵核不在 machines map，fsm == null 直接拒绝。
        // 「灵核不可卸载」语义由「灵核不进状态机」承担——能力是类型的派生属性。
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
     * 这是改变宏观状态的外部唯一入口。根据 {@link RuntimeStatus#TRANSITIONS} 定义，
     * 当状态已进入 {@code STOPPING} 时，即使调用本方法试图切回 {@code ACTIVE} 或 {@code DEGRADED}，
     * 也会被状态机直接拒绝并返回非法，从而锁死下线意图，防止优先级反转。
     *
     * @param lingId Ling 标识
     * @param target 目标状态
     * @return 转换结果
     */
    public TransitionResult<RuntimeStatus> transition(String lingId, RuntimeStatus target) {
        // 迁移合法性委托给 RuntimeStatus.TRANSITIONS 现有转换表，由 StateMachine.transition 内部判定。
        // 灵核不进 machines map，fsm == null 直接返回 illegal。
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
     * 清理已移除 Ling 的内存数据。
     * 仅当当前状态为 {@link RuntimeStatus#REMOVED} 时生效；
     * 全量卸载路径必须先确定性进入 REMOVED，再调用本方法。
     *
     * @return 是否完成清理
     */
    public boolean purge(String lingId) {
        StateMachine<RuntimeStatus> fsm = machines.get(lingId);
        if (fsm != null && fsm.current() == RuntimeStatus.REMOVED) {
            machines.remove(lingId);
            snapshots.remove(lingId);
            log.info("Ling [{}] purged from RuntimeCoordinator", lingId);
            return true;
        }
        return false;
    }

    /**
     * 编排层在「新 runtime 部署失败」或「全量卸载收口」时调用的确定性注销。
     * <p>
     * 不依赖实例快照是否为空：仓库侧已放弃该 ling 时，coordinator 不得残留 ghost 状态机。
     * 语义：
     * <ol>
     *   <li>若尚未 REMOVED：优先走 STOPPING → REMOVED（合法表内路径）</li>
     *   <li>清空快照并从 machines 移除</li>
     * </ol>
     * 幂等：对未注册 ling 直接返回 false。
     *
     * @param lingId 灵元 ID
     * @return 是否移除了已注册条目
     */
    public boolean unregister(String lingId) {
        StateMachine<RuntimeStatus> fsm = machines.get(lingId);
        if (fsm == null) {
            snapshots.remove(lingId);
            return false;
        }

        RuntimeStatus current = fsm.current();
        // 确定性收口到 REMOVED：合法表路径优先
        // INACTIVE 可直达 REMOVED；ACTIVE/DEGRADED/RECOVERING 先 STOPPING 再 REMOVED
        if (current == RuntimeStatus.INACTIVE) {
            forceTransition(lingId, fsm, RuntimeStatus.REMOVED);
        } else if (current == RuntimeStatus.ACTIVE
                || current == RuntimeStatus.DEGRADED
                || current == RuntimeStatus.RECOVERING) {
            forceTransition(lingId, fsm, RuntimeStatus.STOPPING);
            if (fsm.current() == RuntimeStatus.STOPPING) {
                forceTransition(lingId, fsm, RuntimeStatus.REMOVED);
            }
        } else if (current == RuntimeStatus.STOPPING) {
            forceTransition(lingId, fsm, RuntimeStatus.REMOVED);
        }

        RuntimeStatus finalStatus = fsm.current();
        machines.remove(lingId);
        snapshots.remove(lingId);
        log.info("Ling [{}] unregistered from RuntimeCoordinator (final={})", lingId, finalStatus);
        return true;
    }

    private void forceTransition(String lingId, StateMachine<RuntimeStatus> fsm, RuntimeStatus target) {
        TransitionResult<RuntimeStatus> result = fsm.transition(target);
        if (result.isSuccess() && result.from() != result.target()) {
            log.info("Ling [{}] runtime unregister transition: {} -> {}",
                    lingId, result.from(), result.target());
            publishChanged(lingId, result);
        } else if (result.isIllegal()) {
            log.warn("Ling [{}] illegal transition {} -> {} during unregister",
                    lingId, result.from(), target);
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

            // 通用压制规则：运维意图态与终态不可被实例微观事实反向覆盖
            if (current.suppressesEvaluation()) {
                if (current == RuntimeStatus.STOPPING) {
                    tryFinishShutdown(lingId, fsm);
                }
                return;
            }

            // 收集实例状态快照，而不是直接操作 LingInstance：
            // 这样双层状态机是“事件联动”，不是“对象互写”。
            // INACTIVE 是事实态（无可用实例），不是「停流」：有 READY 就应可聚合回 ACTIVE。
            // 流量控制走路由/权重/权限，不占用 RuntimeStatus。
            ConcurrentMap<String, InstanceStatus> states = snapshots.get(lingId);
            Collection<InstanceStatus> stateValues = (states != null)
                    ? states.values()
                    : Collections.emptyList();

            // 调用策略评估
            RuntimeStatus suggested = policy.evaluate(current, stateValues);

            // 策略契约守护：策略评估必须只输出事实态（Kind.FACT）
            if (suggested != null && suggested.kind() != Kind.FACT) {
                log.error("Policy [{}] violated contract: suggested non-fact status [{}] (kind={}) for ling [{}]",
                        policy.getClass().getSimpleName(), suggested, suggested.kind(), lingId);
                return;
            }

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

    /* ==================== DEGRADED 定时健康检查 ==================== */

    private void startHealthCheck() {
        if (healthCheckExecutor != null) {
            return;
        }
        synchronized (this) {
            if (healthCheckExecutor != null) {
                return;
            }
            ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(
                    NamedThreadFactory.daemon("lingframe-degraded-health-check"));
            executor.scheduleAtFixedRate(
                    this::checkDegradedLings,
                    HEALTH_CHECK_INTERVAL_SECONDS,
                    HEALTH_CHECK_INTERVAL_SECONDS,
                    TimeUnit.SECONDS);
            healthCheckExecutor = executor;
            log.info("DEGRADED health check started (interval={}s)", HEALTH_CHECK_INTERVAL_SECONDS);
        }
    }

    private void stopHealthCheck() {
        ScheduledExecutorService executor;
        synchronized (this) {
            executor = healthCheckExecutor;
            if (executor == null) {
                return;
            }
            healthCheckExecutor = null;
        }
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
                log.warn("DEGRADED health check executor did not terminate in 5s, forced shutdown");
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("DEGRADED health check stopped");
    }

    /**
     * 周期性健康检查：DEGRADED 兜底重评 + RECOVERING 卡死兜底收口。
     * <p>
     * 事件驱动路径的兜底：
     * <ul>
     *   <li><b>DEGRADED</b>：当灵元因熔断打开进入 DEGRADED，但没有新请求经过 Pipeline
     *       触发 {@code tryRecoverFromDegraded} 时，此定时任务确保 DEGRADED 灵元最终能被重新评估。</li>
     *   <li><b>RECOVERING</b>：RECOVERING 压制聚合评估（见 {@link #reevaluate}），
     *       若恢复流程失败且未显式收口，灵元会永久卡在 RECOVERING（MacroStateGuardFilter
     *       拒绝其流量）。此处根据实例快照事实兜底收口，防止"失败恢复导致永久流量阻断"。</li>
     * </ul>
     */
    private void checkDegradedLings() {
        try {
            for (String lingId : machines.keySet()) {
                StateMachine<RuntimeStatus> fsm = machines.get(lingId);
                if (fsm == null) {
                    continue;
                }
                RuntimeStatus current = fsm.current();
                if (current == RuntimeStatus.DEGRADED) {
                    log.debug("Health check: reevaluating DEGRADED ling [{}]", lingId);
                    reevaluate(lingId);
                } else if (current == RuntimeStatus.RECOVERING) {
                    recoverStuckRecoveringLing(lingId, fsm);
                }
            }
        } catch (Exception e) {
            log.warn("DEGRADED health check failed: {}", e.getMessage());
        }
    }

    /**
     * RECOVERING 意图态兜底收口。
     * <p>
     * 依据实例快照事实判断恢复进展（与 {@link DefaultRuntimeEvaluationPolicy} 的过渡语义对齐）：
     * <ul>
     *   <li>快照为空（实例全部 DEAD 已移除）→ 恢复意图已无事实支撑，收口 DEGRADED；</li>
     *   <li>存在过渡中实例（CREATED/LOADING/STARTING/RECOVERING）→ 恢复仍在进行，
     *       保持 RECOVERING 不动（避免慢启动期间被误收口造成状态抖动）；</li>
     *   <li>无过渡中实例且存在 READY → 恢复实际已完成，收口 ACTIVE；</li>
     *   <li>无过渡中实例且无 READY → 恢复失败/中断，收口 DEGRADED（事实态，可再自愈）。</li>
     * </ul>
     */
    private void recoverStuckRecoveringLing(String lingId, StateMachine<RuntimeStatus> fsm) {
        ConcurrentMap<String, InstanceStatus> states = snapshots.get(lingId);
        if (states == null || states.isEmpty()) {
            // 空快照 = 无任何实例事实（全部 DEAD 已从快照移除）：
            // 恢复意图无支撑，收口 DEGRADED（FACT 态，后续事件/reevaluate 可再驱动）。
            log.warn("Health check: RECOVERING ling [{}] has no instances, converging to DEGRADED", lingId);
            forceTransition(lingId, fsm, RuntimeStatus.DEGRADED);
            return;
        }
        boolean hasReady = false;
        boolean transitionInProgress = false;
        for (InstanceStatus status : states.values()) {
            if (status == InstanceStatus.READY) {
                hasReady = true;
            } else if (isTransitionInProgress(status)) {
                transitionInProgress = true;
            }
        }
        if (transitionInProgress) {
            // 仍有实例处于恢复/启动过渡中（如 Spring 上下文启动耗时长于健康检查间隔），
            // 保持 RECOVERING，防止把进行中的恢复误收口为 DEGRADED/ACTIVE 造成状态抖动。
            log.debug("Health check: RECOVERING ling [{}] still has instances in transition, keep RECOVERING", lingId);
            return;
        }
        if (hasReady) {
            log.info("Health check: RECOVERING ling [{}] has READY instances and no transition in progress, "
                    + "converging to ACTIVE", lingId);
            forceTransition(lingId, fsm, RuntimeStatus.ACTIVE);
        } else {
            log.warn("Health check: RECOVERING ling [{}] has no ready or transitioning instances, "
                    + "converging to DEGRADED", lingId);
            forceTransition(lingId, fsm, RuntimeStatus.DEGRADED);
        }
    }

    /**
     * 实例状态是否属于"恢复/启动过渡中"。
     * <p>
     * 与 {@link DefaultRuntimeEvaluationPolicy} 的过渡语义保持一致：
     * CREATED/LOADING/STARTING/RECOVERING 均视为恢复尚未收敛，
     * 健康检查不得在此窗口内把 RECOVERING 运行时收口为其他状态。
     */
    private static boolean isTransitionInProgress(InstanceStatus status) {
        return status == InstanceStatus.RECOVERING
                || status == InstanceStatus.CREATED
                || status == InstanceStatus.LOADING
                || status == InstanceStatus.STARTING;
    }
}
