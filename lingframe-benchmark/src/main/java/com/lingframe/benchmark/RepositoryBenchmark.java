package com.lingframe.benchmark;

import com.lingframe.core.ling.DefaultLingRepository;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

/**
 * LingRepository 并发读性能基准测试
 * <p>
 * 测量 DefaultLingRepository.getRuntime() 在多线程并发读下的吞吐量。
 * 这是每次 Pipeline 调用的第一步：MacroStateGuardFilter 需要通过 lingId 查找 LingRuntime。
 * <p>
 * 底层是 ConcurrentHashMap.get()，预期吞吐量极高。
 * <p>
 * 运行方式：
 * <pre>
 * mvn -pl lingframe-benchmark package -Pbenchmark -am -DskipTests
 * java -jar lingframe-benchmark/target/lingframe-benchmarks.jar RepositoryBenchmark -f 3 -prof gc
 * </pre>
 */
@BenchmarkMode({Mode.Throughput, Mode.AverageTime})
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 2)
@Fork(value = 1, jvmArgs = {"-Xms2g", "-Xmx2g", "-XX:+UseG1GC", "-XX:+AlwaysPreTouch", "-Dorg.slf4j.simpleLogger.defaultLogLevel=warn"})
@State(Scope.Benchmark)
public class RepositoryBenchmark {

    private DefaultLingRepository repository;

    /** 灵元数量参数 */
    @Param({"1", "10", "50"})
    private int lingCount;

    /**
     * 线程局部状态：预计算分散 key，避免 benchmark 方法中的字符串拼接噪声。
     * <p>
     * 旧版在 benchmark 方法中做 Thread.currentThread().getId() % lingCount + 字符串拼接，
     * 这些开销可能比被测的 ConcurrentHashMap.get() 本身还大，严重扭曲结果。
     */
    @State(Scope.Thread)
    public static class ThreadState {
        String distributedKey;

        /**
         * 通过外部注入的 RepositoryBenchmark 获取 lingCount。
         * JMH 在 @Setup 中可以注入同一 benchmark 类的 @State 实例。
         */
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
