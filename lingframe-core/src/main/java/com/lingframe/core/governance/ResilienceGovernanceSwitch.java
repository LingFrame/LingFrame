package com.lingframe.core.governance;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 运行时弹性治理总开关。
 * <p>
 * 这是应急降级入口，只控制弹性治理过滤器的执行，不关闭 ClassLoader、卸载和生命周期清理。
 * 使用原子状态保证运维线程切换时，调用线程能看到完整的开关结果。
 */
public final class ResilienceGovernanceSwitch {

    private final AtomicBoolean enabled = new AtomicBoolean(true);

    /**
     * @return 当前是否启用弹性治理
     */
    public boolean isEnabled() {
        return enabled.get();
    }

    /**
     * 切换弹性治理总开关。
     *
     * @param enabled true=启用，false=绕过弹性治理
     */
    public void setEnabled(boolean enabled) {
        this.enabled.set(enabled);
    }
}
