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
     * L0 provider 级路由阶段。
     * <p>
     * 在所有正向阶段之前执行，从 FQSID 中提取契约 ID，
     * 按 provider 权重选择目标 lingId 并设置 ctx.runtime，
     * 让后续过滤器直接使用已解析的 runtime。
     * <p>
     * 旧格式 FQSID（{@code lingId:serviceName}）不触发此阶段，走兼容路径直接放行。
     */
    public static final int PROVIDER_ROUTING = -100;

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
     * 事务上下文穿透阶段。
     * <p>
     * 位置：ROUTING 之后、RESOLUTION（ContextIsolationFilter 类加载器切换）之前，
     * 即 POLICY_PREFILL 与 RESILIENCE 之间——路由确定之后（能拿到 lingId）、
     * TCCL 切换之前（连接尚在同线程）的交汇点。
     * <p>
     * 职责：把上游活跃事务的物理连接按 dataSourceId 推入 {@code LingTransactionContext}，
     * 供下游灵元经受管数据源代理复用；调用返回后回传 rollbackOnly 信号并擦除上下文。
     * 仅 NORMAL 模式穿透；SIMULATION / GOVERN_ONLY 直接放行。
     */
    public static final int TRANSACTION_PROPAGATION = ROUTING + 50;

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
