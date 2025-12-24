package com.lingframe.core.plugin;

import com.lingframe.core.spi.PluginContainer;
import lombok.Getter;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 插件实例：代表一个特定版本的插件运行实体
 * 包含：容器引用 + 引用计数器
 */
@Getter
public class PluginInstance {

    private final String version;

    private final PluginContainer container;

    // 实例固有标签 (如 {"env": "canary", "tenant": "T1"})
    private final Map<String, String> labels = new ConcurrentHashMap<>();

    // 引用计数器：记录当前正在处理的请求数
    private final AtomicLong activeRequests = new AtomicLong(0);

    // 标记是否进入“濒死”状态（不再接收新流量）
    private volatile boolean isDying = false;

    public PluginInstance(String version, PluginContainer container) {
        this.version = version;
        this.container = container;
    }

    /**
     * 请求进入：计数器 +1
     */
    public void enter() {
        activeRequests.incrementAndGet();
    }

    /**
     * 请求退出：计数器 -1
     */
    public void exit() {
        activeRequests.decrementAndGet();
    }

    /**
     * 标记为濒死状态
     */
    public void markDying() {
        this.isDying = true;
    }

    /**
     * 检查是否闲置（无活跃请求）
     */
    public boolean isIdle() {
        return activeRequests.get() <= 0;
    }

    /**
     * 销毁实例
     */
    public void destroy() {
        if (container.isActive()) {
            container.stop();
        }
    }
}