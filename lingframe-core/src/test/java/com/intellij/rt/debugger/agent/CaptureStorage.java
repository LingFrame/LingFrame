package com.intellij.rt.debugger.agent;

/**
 * 模拟 IntelliJ debugger-agent 的 CaptureStorage 类（仅用于测试）。
 * <p>
 * 实际环境中该类由 {@code -javaagent:debugger-agent.jar} 加载，
 * 持有 {@code STORAGE_THROWABLES} 静态字段用于缓存异常 backtrace。
 * 测试环境下通过将此类放入 test classpath，
 * 使 {@code Class.forName(..., ClassLoader.getSystemClassLoader())} 能找到它。
 * <p>
 * 字段类型为 {@code Object} 以兼容不同 storage 类型（Map 或包装器）。
 */
public class CaptureStorage {

    /** 模拟 STORAGE_THROWABLES 静态字段，测试中动态设置 */
    public static Object STORAGE_THROWABLES;
}
