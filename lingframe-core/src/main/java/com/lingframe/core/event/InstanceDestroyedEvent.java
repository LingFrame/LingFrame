package com.lingframe.core.event;

import com.lingframe.api.event.LingEvent;

import java.util.Objects;

/**
 * 实例生命周期终结事件。
 * <p>
 * 由 {@code InstanceCoordinator.tearDown()} 发布，语义为「实例不再参与运行时聚合，
 * 下游可做快照收口和资源回收」——无论物理 destroy 成功或失败均会发布：
 * <ul>
 *   <li>destroy 成功：实例已 DEAD，资源可安全回收</li>
 *   <li>destroy 失败：实例进入 ERROR 并补发本事件，避免 RuntimeCoordinator 快照残留僵尸 ERROR；
 *       下游 {@code DefaultLingResourceManager} 执行的线程池关闭和缓存驱逐不依赖
 *       Bean 是否正常 destroy 完成，对失败场景同样必要且安全</li>
 * </ul>
 * 消费者不应假设「Bean 物理资源已完全释放」，而应理解为「该实例可从运行时快照移除，
 * 与之关联的进程级资源（线程池、句柄缓存）可回收」。
 * <p>
 * {@code instanceId} 用于快照收口；{@code version} 仍用于版本级资源前缀清理。
 */
public class InstanceDestroyedEvent implements LingEvent {
    private final String lingId;
    private final String instanceId;
    private final String version;
    private final long timestamp;

    public InstanceDestroyedEvent(String lingId, String instanceId, String version) {
        this.lingId = Objects.requireNonNull(lingId, "lingId");
        this.instanceId = Objects.requireNonNull(instanceId, "instanceId");
        this.version = version;
        this.timestamp = System.currentTimeMillis();
    }

    public String getLingId() {
        return lingId;
    }

    public String getInstanceId() {
        return instanceId;
    }

    public String getVersion() {
        return version;
    }

    public long getTimestamp() {
        return timestamp;
    }
}
