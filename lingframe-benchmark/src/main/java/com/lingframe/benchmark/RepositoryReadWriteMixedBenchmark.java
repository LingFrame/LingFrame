package com.lingframe.benchmark;

import com.lingframe.core.ling.DefaultLingRepository;
import com.lingframe.core.ling.LingRuntime;
import com.lingframe.core.ling.LingRuntimeConfig;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

/**
 * LingRepository 读写混合基准测试
 * <p>
 * 模拟生产中"一边部署/卸载灵元，一边高频读取"的真实场景：
 * <ul>
 *   <li>{@code readHeavy} — 7 读线程 + 1 写线程（典型生产比例）</li>
 *   <li>{@code balanced} — 4 读线程 + 4 写线程（极端压力对照）</li>
 * </ul>
 * <p>
 * 读线程只访问预部署的稳定灵元（stable-ling-*），
 * 写线程在独立命名空间（volatile-ling-{threadId}）循环 register/deregister，
 * 模拟灵元热部署/热卸载对并发读的干扰。
 * <p>
 * 底层是 ConcurrentHashMap 的 put/remove vs get 竞争，
 * 预期读吞吐仅有轻微下降。
 * <p>
 * 运行方式：
 * <pre>
 * mvn clean -pl lingframe-benchmark package -Pbenchmark -am -DskipTests
 * java -jar lingframe-benchmark/target/lingframe-benchmarks.jar RepositoryReadWriteMixedBenchmark -f 3 -prof gc
 * </pre>
 */
@BenchmarkMode({Mode.Throughput, Mode.AverageTime})
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 2)
@Fork(value = 1, jvmArgs = {"-Xms2g", "-Xmx2g", "-XX:+UseG1GC", "-XX:+AlwaysPreTouch", "-Dorg.slf4j.simpleLogger.defaultLogLevel=warn"})
@State(Scope.Benchmark)
public class RepositoryReadWriteMixedBenchmark {

    /** 预部署的稳定灵元数量 */
    private static final int STABLE_LING_COUNT = 10;

    private DefaultLingRepository repository;
    private BenchmarkDeploymentHelper helper;

    /**
     * 写线程的线程局部状态。
     * <p>
     * 每个写线程持有一个预构建的 LingRuntime 对象，
     * 在 benchmark 方法中交替执行 register/deregister，
     * 避免在热路径中创建对象。
     */
    @State(Scope.Thread)
    public static class WriterState {
        LingRuntime volatileRuntime;
        String volatileKey;
        /** 当前是否已注册到仓储，用于交替执行 register/deregister */
        boolean registered;

        @Setup(Level.Trial)
        public void setup(RepositoryReadWriteMixedBenchmark parent) {
            long tid = Thread.currentThread().getId();
            volatileKey = "volatile-ling-" + tid;
            // 走完整部署路径创建真实 LingRuntime，再从仓储取出用于 register/deregister 循环。
            // 不能绕过 DefaultLingLifecycleEngine 直接 new LingRuntime——InstanceCoordinator
            // 是包级私有写入口，只有 core 内部能创建。完整部署保证状态机一致性。
            parent.helper.deployLing(volatileKey, "1.0.0");
            volatileRuntime = parent.helper.getLingRepository().getRuntime(volatileKey);
            parent.helper.getLingRepository().deregister(volatileKey);
        }
    }

    /**
     * 读线程的线程局部状态。
     * <p>
     * 预计算分散 key，避免所有读线程集中打同一个桶。
     */
    @State(Scope.Thread)
    public static class ReaderState {
        String readKey;

        @Setup(Level.Trial)
        public void setup() {
            int index = (int) (Thread.currentThread().getId() % STABLE_LING_COUNT);
            readKey = "stable-ling-" + index;
        }
    }

    @Setup(Level.Trial)
    public void setup() {
        helper = new BenchmarkDeploymentHelper();
        // 预部署稳定灵元，读线程只访问这些
        for (int i = 0; i < STABLE_LING_COUNT; i++) {
            helper.deployLing("stable-ling-" + i, "1.0.0");
        }
        this.repository = helper.getLingRepository();
    }

    // ===== readHeavy 组：7 读 + 1 写（模拟典型生产负载） =====

    /**
     * readHeavy 组 — 读线程。
     * 7 个线程并发读取预部署的稳定灵元。
     */
    @Benchmark
    @Group("readHeavy")
    @GroupThreads(7)
    public void readHeavy_reader(ReaderState rs, Blackhole bh) {
        bh.consume(repository.getRuntime(rs.readKey));
    }

    /**
     * readHeavy 组 — 写线程。
     * 1 个线程交替执行 register/deregister，模拟灵元热部署干扰。
     */
    @Benchmark
    @Group("readHeavy")
    @GroupThreads(1)
    public void readHeavy_writer(WriterState ws) {
        if (ws.registered) {
            repository.deregister(ws.volatileKey);
            ws.registered = false;
        } else {
            repository.register(ws.volatileRuntime);
            ws.registered = true;
        }
    }

    // ===== balanced 组：4 读 + 4 写（极端压力场景） =====

    /**
     * balanced 组 — 读线程。
     * 4 个线程并发读取。
     */
    @Benchmark
    @Group("balanced")
    @GroupThreads(4)
    public void balanced_reader(ReaderState rs, Blackhole bh) {
        bh.consume(repository.getRuntime(rs.readKey));
    }

    /**
     * balanced 组 — 写线程。
     * 4 个线程各自交替 register/deregister 不同 key。
     */
    @Benchmark
    @Group("balanced")
    @GroupThreads(4)
    public void balanced_writer(WriterState ws) {
        if (ws.registered) {
            repository.deregister(ws.volatileKey);
            ws.registered = false;
        } else {
            repository.register(ws.volatileRuntime);
            ws.registered = true;
        }
    }
}
