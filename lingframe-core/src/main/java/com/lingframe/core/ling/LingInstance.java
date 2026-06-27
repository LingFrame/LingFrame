package com.lingframe.core.ling;

import com.lingframe.api.config.LingDefinition;
import com.lingframe.core.event.EventBus;
import com.lingframe.core.fsm.InstanceStatus;
import com.lingframe.core.fsm.StateMachine;
import com.lingframe.core.fsm.TransitionResult;
import com.lingframe.core.spi.LingContainer;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 灵元实例。
 * <p>
 * 代表某个版本的真实运行实体，内部仍保留实例级状态机作为生命周期一致性原语。
 * 这并不意味着状态机重新暴露给外部：
 * 外部不能直接拿到或修改状态机，所有状态写入都必须经由 {@link InstanceCoordinator}。
 * <p>
 * 换句话说：
 * {@code LingInstance} 拥有“状态承载体”，
 * 但不拥有“对外状态写接口”。
 */
@Slf4j
public class LingInstance {

    // 注意：非 final，destroy() 时需置 null 断开 ClassLoader 引用链
    @Getter
    private volatile LingContainer container;

    // 灵元完整定义（包含治理配置、扩展参数等）
    // 注意：非 final，destroy() 时需置 null
    @Getter
    private volatile LingDefinition definition;

    // 实例固有标签（例如 {"env":"canary","tenant":"T1"}）
    private final Map<String, String> labels = new ConcurrentHashMap<>();

    // 引用计数器：记录当前正在处理的请求数
    private final AtomicLong activeRequests = new AtomicLong(0);
    private final AtomicLong activeInvocationSequence = new AtomicLong(0);
    private final Map<Long, ActiveInvocationSnapshot> activeInvocations = new ConcurrentHashMap<>();

    // 卸载 drain 等待机制：替代此前的 Thread.sleep 轮询。
    // exit() 把引用计数归零时 signal，drain 线程 awaitIdle 阻塞等待，
    // 既消除 CPU 轮询抖动，又能在请求结束的瞬间立即继续卸载，缩短卸载延迟。
    // 使用 ReentrantLock + Condition 而非 synchronized + wait/notify，
    // 以支持 await(timeout) 的精确超时控制，且 Condition 信号不会丢失（await 时已持有锁）。
    private final ReentrantLock idleLock = new ReentrantLock();
    private final Condition idleCondition = idleLock.newCondition();

    // 微观状态机仍然跟随实例对象存在：
    // 1. 它是单实例生命周期的 CAS 一致性载体；
    // 2. 实例状态协调器需要它来原子推进状态；
    // 3. 但它只保留为包内实现细节，不再构成公共 API。
    private final StateMachine<InstanceStatus> stateMachine;

    public LingInstance(LingContainer container, LingDefinition definition, EventBus eventBus) {
        // 参数校验
        this.container = Objects.requireNonNull(container, "container cannot be null");
        this.definition = Objects.requireNonNull(definition, "definition cannot be null");

        String lingId = definition.getId();
        this.stateMachine = InstanceStatus.newMachine(lingId);

        definition.validate();
    }

    public String getVersion() {
        LingDefinition def = definition;
        return def != null ? def.getVersion() : "<destroyed>";
    }

    public String getLingId() {
        LingDefinition def = definition;
        return def != null ? def.getId() : "<destroyed>";
    }

    /**
     * 返回标签的不可变视图，防止外部篡改
     */
    public Map<String, String> getLabels() {
        return Collections.unmodifiableMap(labels);
    }

    /**
     * 安全地添加标签
     */
    public void addLabel(String key, String value) {
        if (key != null && value != null) {
            labels.put(key, value);
        }
    }

    /**
     * 批量添加标签
     */
    public void addLabels(Map<String, String> newLabels) {
        if (newLabels != null) {
            newLabels.forEach(this::addLabel);
        }
    }

    /**
     * 获取当前活跃请求数（不暴露 AtomicLong 本身）
     */
    public long getActiveRequestCount() {
        return activeRequests.get();
    }

    public boolean isReady() {
        LingContainer c = container;
        return currentStatus() == InstanceStatus.READY && c != null && c.isActive();
    }

    public ClassLoader getClassLoader() {
        LingContainer c = container;
        return c != null ? c.getClassLoader() : null;
    }

    public InstanceStatus currentStatus() {
        return stateMachine.current();
    }

    public boolean isDying() {
        InstanceStatus state = currentStatus();
        return state == InstanceStatus.STOPPING || state == InstanceStatus.DEAD || state == InstanceStatus.ERROR;
    }

    public boolean isDestroyed() {
        return currentStatus() == InstanceStatus.DEAD;
    }

    public boolean tryEnter() {
        if (isDying() || !isReady()) {
            return false;
        }
        activeRequests.incrementAndGet();
        if (isDying()) {
            activeRequests.decrementAndGet();
            return false;
        }
        return true;
    }

    public long beginInvocation(ActiveInvocationSnapshot snapshot) {
        if (isDying() || !isReady()) {
            return -1L;
        }

        long invocationId = -1L;
        if (snapshot != null) {
            invocationId = activeInvocationSequence.incrementAndGet();
            activeInvocations.put(invocationId, snapshot);
        }

        activeRequests.incrementAndGet();
        if (isDying()) {
            activeRequests.decrementAndGet();
            if (invocationId > 0) {
                activeInvocations.remove(invocationId);
            }
            return -1L;
        }
        return invocationId;
    }

    public void exit() {
        long count = activeRequests.decrementAndGet();
        if (count < 0) {
            activeRequests.compareAndSet(count, 0);
            log.warn("Unbalanced exit() call detected for ling instance: {}", getVersion());
            count = 0;
        }
        // 引用计数归零时唤醒所有等待 idle 的 drain 线程，
        // 使卸载流程无需轮询即可在请求结束瞬间继续推进。
        if (count == 0) {
            idleLock.lock();
            try {
                idleCondition.signalAll();
            } finally {
                idleLock.unlock();
            }
        }
    }

    /**
     * 阻塞等待实例变为 idle（引用计数归零），最长等待 timeoutMillis 毫秒。
     * <p>
     * 替代卸载路径此前的 {@code Thread.sleep(50)} 轮询：
     * <ul>
     *   <li>消除 CPU 轮询抖动（低活跃场景不再周期性唤醒）；</li>
     *   <li>请求结束的瞬间立即返回，缩短卸载延迟（高活跃场景响应及时）；</li>
     *   <li>超时返回 false 由调用方决定是否强制继续，语义清晰。</li>
     * </ul>
     * 若调用时实例已经 idle，立即返回 true。
     * <p>
     * <b>瞬时快照语义</b>：本方法返回 true 仅代表调用瞬间引用计数为 0，
     * 不保证返回后实例持续 idle——调用方可能在返回后立即有新请求进入。
     * 卸载路径依赖此语义是安全的：drainInstances 已先把实例标记为 STOPPING，
     * {@code isDying()} 为 true 时 {@code tryEnter} 会拒绝新请求，
     * 因此 awaitIdle 返回 true 后实例不会再获得新请求，idle 状态稳定。
     * 调用方在非卸载场景使用本方法时，必须自行处理「返回后又有新请求」的可能。
     *
     * @param timeoutMillis 最长等待毫秒数；<=0 时仅做一次 idle 检查后立即返回
     * @return 实例已 idle 返回 true；超时仍非 idle 返回 false
     * @throws InterruptedException 等待期间线程被中断（调用方应处理中断语义）
     */
    public boolean awaitIdle(long timeoutMillis) throws InterruptedException {
        if (activeRequests.get() == 0) {
            return true;
        }
        if (timeoutMillis <= 0) {
            return false;
        }
        idleLock.lock();
        try {
            // 二次检查防止在获取锁期间实例已变 idle（避免漏信号）
            if (activeRequests.get() == 0) {
                return true;
            }
            return idleCondition.await(timeoutMillis, TimeUnit.MILLISECONDS);
        } finally {
            idleLock.unlock();
        }
    }

    public void completeInvocation(long invocationId) {
        if (invocationId > 0) {
            activeInvocations.remove(invocationId);
        }
        exit();
    }

    public boolean isIdle() {
        return activeRequests.get() == 0;
    }

    public List<ActiveInvocationSnapshot> snapshotActiveInvocations() {
        List<ActiveInvocationSnapshot> snapshots = new ArrayList<>(activeInvocations.values());
        snapshots.sort(Comparator.comparingLong(ActiveInvocationSnapshot::getStartTimeMillis));
        return snapshots;
    }

    // 只做“断开强引用”，不做状态迁移；状态迁移由 InstanceCoordinator 统一负责。
    synchronized void clearDetachedState() {
        labels.clear();
        activeInvocations.clear();
        this.container = null;
        this.definition = null;
    }

    // 包内唯一底层状态写操作，供 InstanceCoordinator 调用。
    // 这里不向外公开，防止业务层或灵核层绕开协调器直接改状态。
    TransitionResult<InstanceStatus> transitionState(InstanceStatus target) {
        return stateMachine.transition(target);
    }

    @Override
    public String toString() {
        return String.format("LingInstance{version='%s', state=%s, activeRequests=%d}",
                getVersion(), currentStatus(), activeRequests.get());
    }
}
