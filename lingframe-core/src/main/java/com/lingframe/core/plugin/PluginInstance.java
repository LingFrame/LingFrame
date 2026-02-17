package com.lingframe.core.plugin;

import com.lingframe.api.config.PluginDefinition;
import com.lingframe.core.spi.PluginContainer;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 插件实例：代表一个特定版本的插件运行实体
 * 包含：容器引用 + 引用计数器 + 完整定义契约
 */
@Slf4j
public class PluginInstance {

    // 🔥 非 final：destroy() 时必须置 null 断开 → ClassLoader 引用链
    private volatile PluginContainer container;

    // 插件完整定义 (包含治理配置、扩展参数等)
    // 🔥 非 final：destroy() 时必须置 null
    private volatile PluginDefinition definition;

    // 实例固有标签 (如 {"env": "canary", "tenant": "T1"})
    private final Map<String, String> labels = new ConcurrentHashMap<>();

    // 引用计数器：记录当前正在处理的请求数
    private final AtomicLong activeRequests = new AtomicLong(0);

    // 标记是否进入“濒死”状态（不再接收新流量）
    @Getter
    private volatile boolean dying = false;

    // 就绪状态
    private volatile boolean ready = false;

    // 🔥 销毁标记，保证幂等
    @Getter
    private volatile boolean destroyed = false;

    public PluginInstance(PluginContainer container, PluginDefinition definition) {
        // 🔥 参数校验
        this.container = Objects.requireNonNull(container, "container cannot be null");
        this.definition = Objects.requireNonNull(definition, "definition cannot be null");

        definition.validate();
    }

    public PluginContainer getContainer() {
        return container;
    }

    public PluginDefinition getDefinition() {
        return definition;
    }

    public String getVersion() {
        PluginDefinition def = definition;
        return def != null ? def.getVersion() : "<destroyed>";
    }

    public String getPluginId() {
        PluginDefinition def = definition;
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

    /**
     * 标记实例就绪
     */
    public void markReady() {
        this.ready = true;
        log.debug("Plugin instance {} marked as ready", definition.getVersion());
    }

    /**
     * 检查是否就绪
     */
    public boolean isReady() {
        PluginContainer c = container;
        return ready
                && !dying
                && !destroyed
                && c != null
                && c.isActive();
    }

    /**
     * 🔥 尝试进入（原子操作，检查状态）
     *
     * @return true 如果成功进入，false 如果实例不可用
     */
    public boolean tryEnter() {
        // 快速检查（非原子，但能过滤大部分无效请求）
        if (dying || destroyed || !ready) {
            return false;
        }

        // 增加计数
        activeRequests.incrementAndGet();

        // 二次检查（防止在 incrementAndGet 之前状态变化）
        if (dying || destroyed) {
            activeRequests.decrementAndGet();
            return false;
        }

        return true;
    }

    /**
     * 请求退出：计数器 -1
     * 防止计数器变负
     */
    public void exit() {
        long count = activeRequests.decrementAndGet();
        if (count < 0) {
            // 修正为 0，并记录警告
            activeRequests.compareAndSet(count, 0);
            log.warn("Unbalanced exit() call detected for plugin instance: {}, count was: {}",
                    definition.getVersion(), count);
        }
    }

    /**
     * 标记为濒死状态
     */
    public void markDying() {
        this.dying = true;
        log.info("Plugin instance {} marked as dying", definition.getVersion());
    }

    /**
     * 检查是否闲置（无活跃请求）
     */
    public boolean isIdle() {
        return activeRequests.get() == 0;
    }

    /**
     * 销毁实例
     * 🔥 保证幂等，增加状态标记
     */
    public synchronized void destroy() {
        if (destroyed) {
            return;
        }

        String version = getVersion();
        this.dying = true;
        this.ready = false;
        this.destroyed = true;

        PluginContainer c = this.container;
        if (c != null && c.isActive()) {
            try {
                c.stop();
                log.info("Plugin instance {} destroyed successfully", version);
            } catch (Exception e) {
                log.error("Error destroying plugin instance {}: {}", version, e.getMessage(), e);
            }
        }

        labels.clear();

        // 🔥 关键：主动断开引用链 container → ClassLoader, definition → metadata
        this.container = null;
        this.definition = null;
    }

    /**
     * 🔥 toString 便于调试
     */
    @Override
    public String toString() {
        return String.format("PluginInstance{version='%s', ready=%s, dying=%s, destroyed=%s, activeRequests=%d}",
                getVersion(), ready, dying, destroyed, activeRequests.get());
    }
}