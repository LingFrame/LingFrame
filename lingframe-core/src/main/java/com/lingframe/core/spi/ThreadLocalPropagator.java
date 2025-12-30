package com.lingframe.core.spi;

/**
 * 上下文传播器 SPI
 * 用于在跨线程（跨插件）调用时，搬运 ThreadLocal 数据
 */
public interface ThreadLocalPropagator<T> {

    /**
     * 在主线程（调用方）捕获当前状态
     */
    T capture();

    /**
     * 在子线程（执行方）重放状态
     *
     * @return 也就是 capture 返回的对象，用于后续 restore
     */
    T replay(T snapshot);

    /**
     * 在子线程（执行方）清理状态
     */
    void restore(T snapshot);
}