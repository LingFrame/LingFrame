package com.lingframe.core.pipeline;

/**
 * 调用执行模式。
 * ⚠️ 这里故意使用枚举，而不是 dryRun / skipTerminalInvocation 之类的松散布尔组合。
 * 原因很简单：多个布尔位很容易组合出非法状态，长期维护后 nobody knows 哪几个组合才算合法。
 */
public enum InvocationExecutionMode {
    /**
     * 正常执行治理链路和终端调用。
     */
    NORMAL(true, false),

    /**
     * 进入完整治理链路，但终端只做模拟，不产生真实副作用。
     * 适用于演练、推演、控制台模拟等场景。
     */
    SIMULATION(true, true),

    /**
     * 仅执行治理链路，不进入终端调用。
     * 适用于灵核 Web / AOP 入口“借道 Pipeline 做治理”，真实业务执行仍由原框架负责的场景。
     */
    GOVERN_ONLY(false, false);

    private final boolean invokeTerminal;
    private final boolean simulation;

    InvocationExecutionMode(boolean invokeTerminal, boolean simulation) {
        this.invokeTerminal = invokeTerminal;
        this.simulation = simulation;
    }

    public boolean shouldInvokeTerminal() {
        return invokeTerminal;
    }

    public boolean isSimulation() {
        return simulation;
    }

    public boolean isGovernOnly() {
        return !invokeTerminal;
    }
}
