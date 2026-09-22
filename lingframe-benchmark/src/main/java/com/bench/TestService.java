package com.bench;

/**
 * Benchmark 专用服务类
 * <p>
 * 类名与 Pipeline FQSID 中的服务名一致（bench-ling:com.bench.TestService），
 * 使 TerminalInvokerFilter 的 classLoader.loadClass("com.bench.TestService") 一次命中，
 * 避免每次迭代走 ClassNotFoundException → 兜底 getBean(String) 的噪声路径。
 * <p>
 * 方法体只返回常量，不引入任何业务逻辑噪声。
 */
public class TestService {
    public String ping() {
        return "pong";
    }
}
