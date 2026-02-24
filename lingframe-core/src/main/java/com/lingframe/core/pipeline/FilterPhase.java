package com.lingframe.core.pipeline;

public final class FilterPhase {
    public static final int METRICS = 0;
    public static final int STATE_GUARD = 50;
    public static final int ROUTING = 100;
    public static final int RESILIENCE = 200;
    public static final int ISOLATION = 300;
    public static final int TERMINAL = Integer.MAX_VALUE;

    // 第三方插件建议区间
    public static final int USER_BEFORE_ROUTING = 80;
    public static final int USER_BEFORE_INVOKE = 5000;

    private FilterPhase() {
    }
}
