package com.lingframe.api.event.lifecycle;

/**
 * 卸载完成事件
 * 场景：清理临时文件
 * <p>
 * 在泄漏验证（ClassLoader GC 回收确认）完成后发出，{@link #isClassLoaderLeaked()}
 * 如实反映验证结论，消费方（如 Dashboard 列表刷新）据此区分干净卸载与存在泄漏的卸载。
 */
public class LingUninstalledEvent extends LingLifecycleEvent {

    private final boolean classLoaderLeaked;

    public LingUninstalledEvent(String lingId) {
        this(lingId, false);
    }

    public LingUninstalledEvent(String lingId, boolean classLoaderLeaked) {
        super(lingId, null);
        this.classLoaderLeaked = classLoaderLeaked;
    }

    public boolean isClassLoaderLeaked() {
        return classLoaderLeaked;
    }
}
