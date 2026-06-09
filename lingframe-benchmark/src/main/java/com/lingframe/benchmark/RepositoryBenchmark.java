package com.lingframe.benchmark;

import com.lingframe.core.ling.DefaultLingRepository;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

/**
 * LingRepository 并发读性能基准测试
 * <p>
 * 测量 DefaultLingRepository.getRuntime() 在多线程并发读下的吞吐量。
 * <p>
 * 【学术级性能说明：JDK 8 仓储扩展机制】
 * 此基准参数扩充为 1, 50, 500, 2000。
 * 在 JDK 8 的 ConcurrentHashMap 实现中，已经移除了 JDK 7 的 Segment 分段锁。
 * 真正影响吞吐与延迟退化拐点的是：
 * 1. 数组扩容（超出阈值 initialCapacity * loadFactor ≈ 12 时触发 Node 数组 resize）。
 * 2. 桶链表树化（单桶链表长度超过 TREEIFY_THRESHOLD = 8 且数组长度 >= 64 时退化为红黑树）。
 * 本测试参数跨越千级，能清晰展示在桶扩容与部分桶树化检索时框架底座的稳定性。
 * <p>
 * 运行方式：
 * <pre>
 * mvn -pl lingframe-benchmark package -Pbenchmark -am -DskipTests
 * java -jar lingframe-benchmark/target/lingframe-benchmarks.jar RepositoryBenchmark -f 3
 * </pre>
 */
@BenchmarkMode({Mode.Throughput, Mode.AverageTime})
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 2)
@Fork(value = 1, jvmArgs = {
        "-Xms2g", "-Xmx2g", "-XX:+UseG1GC", "-XX:+AlwaysPreTouch",
        "-Dorg.slf4j.simpleLogger.defaultLogLevel=warn",
        "-XX:CompileCommand=dontinline,com/lingframe/core/pipeline/InvocationPipelineEngine::invoke",
        "-XX:CompileCommand=dontinline,com/lingframe/core/event/EventBus::publish"
})
@State(Scope.Benchmark)
public class RepositoryBenchmark {

    private DefaultLingRepository repository;

    /** 灵元数量参数，用于探测扩容再哈希与树化性能拐点 */
    @Param({"1", "50", "500", "2000"})
    private int lingCount;

    /**
     * 线程局部状态：预计算分散 key，避免 benchmark 方法中的字符串拼接噪声。
     */
    @State(Scope.Thread)
    public static class ThreadState {
        String distributedKey;

        @Setup(Level.Trial)
        public void setup(RepositoryBenchmark parent) {
            int threadIndex = (int) (Thread.currentThread().getId() % parent.lingCount);
            distributedKey = "bench-ling-" + threadIndex;
        }
    }

    @Setup(Level.Trial)
    public void setup() {
        BenchmarkDeploymentHelper helper = new BenchmarkDeploymentHelper();
        for (int i = 0; i < lingCount; i++) {
            helper.deployLing("bench-ling-" + i, "1.0.0");
        }
        this.repository = helper.getLingRepository();
    }

    /**
     * 单线程 getRuntime 基线
     */
    @Benchmark
    @Threads(1)
    public void getRuntime_1Thread(Blackhole bh) {
        bh.consume(repository.getRuntime("bench-ling-0"));
    }

    /**
     * 4 线程并发 getRuntime
     */
    @Benchmark
    @Threads(4)
    public void getRuntime_4Threads(Blackhole bh) {
        bh.consume(repository.getRuntime("bench-ling-0"));
    }

    /**
     * 8 线程并发 getRuntime
     */
    @Benchmark
    @Threads(8)
    public void getRuntime_8Threads(Blackhole bh) {
        bh.consume(repository.getRuntime("bench-ling-0"));
    }

    /**
     * 8 线程并发 getRuntime（分散到不同 lingId，减少争用）
     * <p>
     * 使用预计算的 distributedKey，消除每次迭代的字符串拼接噪声。
     */
    @Benchmark
    @Threads(8)
    public void getRuntime_8Threads_Distributed(ThreadState ts, Blackhole bh) {
        bh.consume(repository.getRuntime(ts.distributedKey));
    }

    /**
     * 单线程 hasRuntime 基线
     */
    @Benchmark
    @Threads(1)
    public void hasRuntime_1Thread(Blackhole bh) {
        bh.consume(repository.hasRuntime("bench-ling-0"));
    }

    /**
     * 8 线程并发 hasRuntime
     */
    @Benchmark
    @Threads(8)
    public void hasRuntime_8Threads(Blackhole bh) {
        bh.consume(repository.hasRuntime("bench-ling-0"));
    }
}
