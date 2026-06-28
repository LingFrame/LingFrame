package com.lingframe.core.spi;

/**
 * 指标采集器内核抽象。
 * <p>
 * 微内核解耦：内核（ling 包）只依赖此接口，具体实现由 metrics 扩展包提供。
 */
public interface LingMetricsCollector {
}
