package com.intellij.rt.debugger.agent;

/**
 * 模拟 IntelliJ debugger-agent 的 ExceptionCapturedStack（仅用于测试）。
 * <p>
 * 该类持有 {@code myException}（Throwable）字段，
 * 类名以 {@code com.intellij.} 开头，触发
 * {@link com.lingframe.core.resource.DebuggerCaptureUnloadHook}
 * 的 {@code scanIntelliJFields} 反射扫描路径。
 */
public class ExceptionCapturedStack {

    /** 持有 Throwable 引用，是泄漏链的关键节点 */
    public Throwable myException;

    public ExceptionCapturedStack(Throwable t) {
        this.myException = t;
    }
}
