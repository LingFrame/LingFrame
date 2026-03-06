package com.lingframe.core.ling;

import com.lingframe.api.config.LingDefinition;
import com.lingframe.core.fsm.InstanceStatus;
import com.lingframe.core.fsm.StateMachine;
import com.lingframe.core.spi.LingContainer;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 灵元实例：代表一个特定版本的灵元运行实体
 * 在 V0.3.0 中已采用 FSM (StateMachine) 进行生命周期防篡改保护。
 */
@Slf4j
public class LingInstance {

    // 🔥 非 final：destroy() 时必须置 null 断开 → ClassLoader 引用链
    @Getter
    private volatile LingContainer container;

    // 灵元完整定义 (包含治理配置、扩展参数等)
    // 🔥 非 final：destroy() 时必须置 null
    @Getter
    private volatile LingDefinition definition;

    // 实例固有标签 (如 {"env": "canary", "tenant": "T1"})
    private final Map<String, String> labels = new ConcurrentHashMap<>();

    // 引用计数器：记录当前正在处理的请求数
    private final AtomicLong activeRequests = new AtomicLong(0);

    // 微观状态机
    @Getter
    private final StateMachine<InstanceStatus> stateMachine = InstanceStatus.newMachine();

    public LingInstance(LingContainer container, LingDefinition definition) {
        // 🔥 参数校验
        this.container = Objects.requireNonNull(container, "container cannot be null");
        this.definition = Objects.requireNonNull(definition, "definition cannot be null");

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
     * 🔥 返回标签的不可变视图，防止外部篡改
     */
    public Map<String, String> getLabels() {
        return Collections.unmodifiableMap(labels);
    }

    /**
     * 🔥 安全地添加标签
     */
    public void addLabel(String key, String value) {
        if (key != null && value != null) {
            labels.put(key, value);
        }
    }

    /**
     * 🔥 批量添加标签
     */
    public void addLabels(Map<String, String> newLabels) {
        if (newLabels != null) {
            newLabels.forEach(this::addLabel);
        }
    }

    /**
     * 🔥 获取当前活跃请求数（不暴露 AtomicLong 本身）
     */
    public long getActiveRequestCount() {
        return activeRequests.get();
    }

    public boolean isReady() {
        LingContainer c = container;
        return stateMachine.current() == InstanceStatus.READY && c != null && c.isActive();
    }

    public void markReady() {
        stateMachine.transition(InstanceStatus.READY);
    }

    public void markDying() {
        stateMachine.transition(InstanceStatus.STOPPING);
    }

    public boolean isDying() {
        InstanceStatus state = stateMachine.current();
        return state == InstanceStatus.STOPPING || state == InstanceStatus.DEAD || state == InstanceStatus.ERROR;
    }

    public boolean isDestroyed() {
        return stateMachine.current() == InstanceStatus.DEAD;
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
            log.warn("Unbalanced exit() call detected for ling instance: {}", definition.getVersion());
        }
    }

    public boolean isIdle() {
        return activeRequests.get() == 0;
    }

    public synchronized void destroy() {
        if (stateMachine.current() == InstanceStatus.DEAD) {
            return;
        }

        try {
            InstanceStatus current = stateMachine.current();
            if (current == InstanceStatus.CREATED || current == InstanceStatus.LOADING
                    || current == InstanceStatus.STARTING) {
                stateMachine.transition(InstanceStatus.ERROR);
                stateMachine.transition(InstanceStatus.DEAD);
            } else if (current == InstanceStatus.READY) {
                stateMachine.transition(InstanceStatus.STOPPING);
                stateMachine.transition(InstanceStatus.DEAD);
            } else if (current == InstanceStatus.STOPPING || current == InstanceStatus.ERROR) {
                stateMachine.transition(InstanceStatus.DEAD);
            }
        } catch (Exception e) {
            log.warn("Forced state transition to DEAD during destroy failed: {}", e.getMessage());
        }

        String version = getVersion();

        LingContainer c = this.container;
        if (c != null && c.isActive()) {
            try {
                c.stop();
                log.info("Ling instance {} destroyed successfully", version);
            } catch (Exception e) {
                log.error("Error destroying ling instance {}: {}", version, e.getMessage(), e);
            }
        }

        labels.clear();
        this.container = null;
        this.definition = null;
    }

    @Override
    public String toString() {
        return String.format("LingInstance{version='%s', state=%s, activeRequests=%d}",
                getVersion(), stateMachine.current(), activeRequests.get());
    }
}