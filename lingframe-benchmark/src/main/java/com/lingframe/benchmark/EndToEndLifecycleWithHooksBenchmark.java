package com.lingframe.benchmark;

import org.openjdk.jmh.annotations.*;

import java.util.concurrent.TimeUnit;

/**
 * 端到端生命周期性能基准测试（装配真实 JVM 资源清理钩子版）
 * <p>
 * 与 {@link EndToEndLifecycleBenchmark} 互为对照：
 * <ul>
 *   <li>前者装配空卸载钩子列表，剥离资源清理噪声，专注状态机纯 CPU 编排成本</li>
 *   <li>本测试装配与生产 {@code LingFrameLifecycleBeansConfiguration.jvmHooks} 一致的 6 个 JVM 桶钩子，
 *       使卸载路径包含反射扫描与资源清理的真实开销</li>
 * </ul>
 * <p>
 * 两组数据相减即可量化资源清理钩子的增量成本：
 * <blockquote>钩子增量 ≈ WithHooks 耗时 − EndToEndLifecycle 耗时</blockquote>
 * <p>
 * 注意：Spring 生态桶钩子（SpringEcosystemUnloadHook/BindConverterCacheCleaner 等）为包级可见，
 * benchmark 模块无法直接构造，故生态桶始终为空。因此本测试量化的是
 * <b>JVM 桶 6 个钩子</b>的增量成本，生产真实总开销还会更高。
 * <p>
 * 运行方式：
 * <pre>
 * mvn -pl lingframe-benchmark package -Pbenchmark -am -DskipTests
 * java -jar lingframe-benchmark/target/lingframe-benchmarks.jar EndToEndLifecycleWithHooksBenchmark -f 3
 * </pre>
 */
@BenchmarkMode({ Mode.AverageTime, Mode.SampleTime })
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 3)
@Fork(value = 1, jvmArgs = {
        "-Xms2g", "-Xmx2g", "-XX:+UseG1GC", "-XX:+AlwaysPreTouch",
        "-Dorg.slf4j.simpleLogger.defaultLogLevel=warn",
        "-XX:CompileCommand=dontinline,com/lingframe/core/pipeline/InvocationPipelineEngine::invoke",
        "-XX:CompileCommand=dontinline,com/lingframe/core/event/EventBus::publish"
})
@State(Scope.Benchmark)
public class EndToEndLifecycleWithHooksBenchmark {

    private BenchmarkDeploymentHelper helper;

    @Setup(Level.Trial)
    public void setup() {
        // 装配真实 JVM 资源清理钩子，使卸载路径与生产一致
        helper = new BenchmarkDeploymentHelper(true);
    }

    /**
     * 每个线程专属的灵元 ID，防止并发测试时不同线程操作同一个灵元引起状态机校验冲突。
     */
    @State(Scope.Thread)
    public static class ThreadState {
        String lingId;

        @Setup(Level.Trial)
        public void setup() {
            lingId = "lifecycle-hooks-bench-ling-" + Thread.currentThread().getId();
        }
    }

    /**
     * 单线程部署与卸载完整生命周期环路测试（含真实 JVM 资源清理钩子）
     */
    @Benchmark
    @Threads(1)
    public void deployAndUndeploy_1Thread(ThreadState ts) {
        helper.deployLing(ts.lingId, "1.0.0");
        helper.undeployLing(ts.lingId);
    }

    /**
     * 4 线程并发部署与卸载生命周期测试（含真实 JVM 资源清理钩子）
     * <p>
     * 验证在高并发多灵元部署/卸载极值场景下，资源清理钩子的并行执行
     * 是否会成为吞吐瓶颈（钩子执行使用 4 线程并行池）。
     */
    @Benchmark
    @Threads(4)
    public void deployAndUndeploy_4Threads(ThreadState ts) {
        helper.deployLing(ts.lingId, "1.0.0");
        helper.undeployLing(ts.lingId);
    }
}
