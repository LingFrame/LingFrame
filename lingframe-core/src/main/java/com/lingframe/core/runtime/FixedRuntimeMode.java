package com.lingframe.core.runtime;

/**
 * 不可变运行时模式实现。
 * <p>
 * 从配置基线读取，构造后不可变，不支持运行时切换。
 * 用于默认配置、测试场景，或不需要运行时切换的生产部署。
 */
public final class FixedRuntimeMode implements RuntimeMode {

    private final boolean dev;
    private final boolean switchEnabled;

    /**
     * 构造不可变运行时模式。
     *
     * @param dev           是否开发模式
     * @param switchEnabled 是否允许运行时切换（通常为 false）
     */
    public FixedRuntimeMode(boolean dev, boolean switchEnabled) {
        this.dev = dev;
        this.switchEnabled = switchEnabled;
    }

    /**
     * 便捷工厂：不可切换的固定模式。
     *
     * @param dev 是否开发模式
     * @return 不可变、不可切换的 RuntimeMode
     */
    public static RuntimeMode fixed(boolean dev) {
        return new FixedRuntimeMode(dev, false);
    }

    @Override
    public boolean isDev() {
        return dev;
    }

    @Override
    public boolean isSwitchEnabled() {
        return switchEnabled;
    }
}
