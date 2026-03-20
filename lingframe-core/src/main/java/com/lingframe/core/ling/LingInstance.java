package com.lingframe.core.ling;

import com.lingframe.api.config.LingDefinition;
import com.lingframe.core.event.EventBus;
import com.lingframe.core.fsm.InstanceStatus;
import com.lingframe.core.fsm.StateMachine;
import com.lingframe.core.fsm.TransitionResult;
import com.lingframe.core.spi.LingContainer;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

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

    public void exit() {
        long count = activeRequests.decrementAndGet();
        if (count < 0) {
            activeRequests.compareAndSet(count, 0);
            log.warn("Unbalanced exit() call detected for ling instance: {}", getVersion());
        }
    }

    public boolean isIdle() {
        return activeRequests.get() == 0;
    }

    // 只做“断开强引用”，不做状态迁移；状态迁移由 InstanceCoordinator 统一负责。
    synchronized void clearDetachedState() {
        labels.clear();
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
