package com.intellij.rt.debugger.agent;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 模拟 IntelliJ debugger-agent 的 ConcurrentIdentityWeakHashMap 包装器（仅用于测试）。
 * <p>
 * 该类不实现 {@code Map} 接口，在内部 {@code map} 字段中持有 {@code ConcurrentHashMap}。
 * 测试 {@link com.lingframe.core.resource.DebuggerCaptureUnloadHook} 的
 * {@code resolveBackingMap} 反射访问 {@code map} 字段路径。
 */
public class ConcurrentIdentityWeakHashMap {

    /** 内部 map 字段，模拟实际 debugger-agent 的包装结构 */
    public ConcurrentHashMap<Object, Object> map = new ConcurrentHashMap<>();
}
