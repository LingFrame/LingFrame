package com.lingframe.core.pipeline;

/**
 * Pipeline 阶段常量。
 * 每个阶段代表的是职责边界，而不是单纯的排序数字。
 * <p>
 * ⚠️ 这些数字背后有明确语义：
 * 1. 先确认运行时是否还能接单
 * 2. 再做路由与弹性治理
 * 3. 再切到目标 ClassLoader 解析方法视角
 * 4. 再做治理决策与权限校验
 * 5. 最后才进入线程隔离和终端执行
 * <p>
 * 如果把 phase 当作“随便调一调顺序也无所谓”的排序号，运行期通常不会立刻编译报错，
 * 但会出现治理读不到解析结果、终端执行绕过隔离之类的隐性错位。
 */
public final class FilterPhase {

    /**
     * 指标与入口打点阶段。
     */
    public static final int METRICS = 0;

    /**
     * 宏观运行时状态守卫阶段。
     */
    public static final int STATE_GUARD = 100;

    /**
     * 路由选路阶段。
     */
    public static final int ROUTING = 200;

    /**
     * 治理意图预填充阶段。
     * <p>
     * 在弹性治理之前把灵元级 effective policy（静态策略 + 动态补丁合并）的 invocation 字段
     * 预填到 ctx.governance()，让弹性组件通过 ctx 读取治理意图，
     * 守护"ctx 为 pipeline 唯一通行证"原则。
     * <p>
     * 必须在 ROUTING 之后（lingId 已确定）、RESILIENCE 之前（弹性组件读 ctx）。
     */
    public static final int POLICY_PREFILL = 240;

    /**
     * 弹性治理阶段。
     */
    public static final int RESILIENCE = 300;

    /**
     * 解析与 ClassLoader 隔离准备阶段。
     */
    public static final int RESOLUTION = 400;

    /**
     * 治理决策阶段。
     */
    public static final int GOVERNANCE = 500;

    /**
     * 终端执行前的线程隔离阶段。
     */
    public static final int EXECUTION_ISOLATION = 600;

    /**
     * 终端调用阶段。
     */
    public static final int TERMINAL = Integer.MAX_VALUE;

    /**
     * 用户扩展在路由前的推荐插入点。
     * 适合做入口增强、预标记和上下文补充。
     */
    public static final int USER_BEFORE_ROUTING = 180;

    /**
     * 用户扩展在终端前的推荐插入点。
     * 适合做结果修饰、埋点、灰度扩展，但不应破坏核心阶段契约。
     */
    public static final int USER_BEFORE_TERMINAL = 9000;

    private FilterPhase() {
    }
}
