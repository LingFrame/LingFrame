package com.lingframe.benchmark;

import org.openjdk.jmh.annotations.*;

import java.util.concurrent.TimeUnit;

/**
 * 端到端生命周期操作性能基准测试
 * <p>
 * 测量控制面中灵元的部署（deploy）与卸载（undeploy）完整闭环开销。
 * 该过程由双层状态机（RuntimeCoordinator + InstanceCoordinator）进行顺序编排与状态推进，
 * 并伴随有 Filter 链缓存重建、事件总线监听注册/注销以及资源回收清理工作。
 * <p>
 * 本基准测试采用内存虚拟的 ClassLoader，规避磁盘 I/O 带来的外部随机延迟抖动，
 * 从而专注于测量框架核心生命周期状态机及管理容器的纯 CPU 编排成本。
 * <p>
 * 运行方式：
 * <pre>
 * mvn -pl lingframe-benchmark package -Pbenchmark -am -DskipTests
 * java -jar lingframe-benchmark/target/lingframe-benchmarks.jar EndToEndLifecycleBenchmark -f 3
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
public class EndToEndLifecycleBenchmark {

    private BenchmarkDeploymentHelper helper;

    @Setup(Level.Trial)
    public void setup() {
        helper = new BenchmarkDeploymentHelper();
    }

    /**
     * 每个线程专属的灵元 ID，防止并发测试时不同线程操作同一个灵元引起状态机校验冲突。
     */
    @State(Scope.Thread)
    public static class ThreadState {
        String lingId;

        @Setup(Level.Trial)
        public void setup() {
            lingId = "lifecycle-bench-ling-" + Thread.currentThread().getId();
        }
    }

    /**
     * 单线程部署与卸载完整生命周期环路测试
     */
    @Benchmark
    @Threads(1)
    public void deployAndUndeploy_1Thread(ThreadState ts) {
        helper.deployLing(ts.lingId, "1.0.0");
        helper.undeployLing(ts.lingId);
    }

    /**
     * 4 线程并发部署与卸载生命周期测试
     * <p>
     * 验证在高并发多灵元部署/卸载极值场景下，控制面状态协调器与生命周期引擎的吞吐及锁开销。
     */
    @Benchmark
    @Threads(4)
    public void deployAndUndeploy_4Threads(ThreadState ts) {
        helper.deployLing(ts.lingId, "1.0.0");
        helper.undeployLing(ts.lingId);
    }
}
