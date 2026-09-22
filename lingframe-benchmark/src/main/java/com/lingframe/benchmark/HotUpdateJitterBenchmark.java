package com.lingframe.benchmark;

import com.lingframe.api.security.AccessType;
import com.lingframe.core.pipeline.InvocationContext;
import com.lingframe.core.pipeline.InvocationExecutionMode;
import com.lingframe.core.pipeline.InvocationPipelineEngine;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 并发热更新抖动观测基准测试。
 * <p>
 * 模拟生产「高并发流量中反复热更新/热卸载」场景：多线程持续执行 NORMAL 全链调用
 * （12 个内置 Filter + 终端执行），同时在调用流中周期性地对独立灵元执行
 * 热更新（部署新版本 → 卸载旧版本，多版本滚动）。
 * <p>
 * 观测目标：
 * <ul>
 *   <li><b>吞吐抖动</b>：对比无热更新基线（{@code steadyStateInvoke}）与热更新进行中
 *       （{@code hotUpdateJitterInvoke}）的吞吐/延迟差——差值即热更新引入的抖动成本；</li>
 *   <li><b>锁竞争</b>：8 线程并发热更新时共享 EventBus / 状态机 / 仓库的争用是否成为瓶颈
 *       （热更新线程与流量线程交织，锁竞争直接体现在延迟分布长尾）。</li>
 * </ul>
 * <p>
 * 运行方式：
 * <pre>
 * mvn -pl lingframe-benchmark package -Pbenchmark -am -DskipTests
 * java -jar lingframe-benchmark/target/lingframe-benchmarks.jar HotUpdateJitterBenchmark -f 1 -t 8
 * </pre>
 */
@BenchmarkMode({Mode.Throughput, Mode.AverageTime})
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
public class HotUpdateJitterBenchmark {

    /** 单批次调用量（同时作为热更新间隔的默认值） */
    private static final int BATCH_SIZE = 100;

    /**
     * 热更新触发间隔（每 N 次调用插入一次热更新）。
     * <p>
     * 默认 100（每批次 1 次，贴近生产低频热更新场景）；抖动灵敏度不足时可缩小，
     * 例如 {@code -Dlingframe.benchmark.hotUpdateBatchSize=10} 放大抖动信号与锁竞争长尾。
     */
    private static final int HOT_UPDATE_INTERVAL =
            Integer.getInteger("lingframe.benchmark.hotUpdateBatchSize", BATCH_SIZE);

    private InvocationPipelineEngine pipelineEngine;
    private BenchmarkDeploymentHelper helper;
    private final AtomicInteger hotVersion = new AtomicInteger(0);

    @Setup(Level.Trial)
    public void setup() {
        helper = new BenchmarkDeploymentHelper();
        // 流量侧灵元：稳态部署，全程不参与热更新
        helper.deployLing("bench-ling", "1.0.0");
        pipelineEngine = helper.getPipelineEngine();
    }

    /**
     * 执行一次 NORMAL 全链调用（治理 + 终端执行，最接近生产路径）。
     */
    private void invokeOnce(Blackhole bh) {
        InvocationContext ctx = InvocationContext.obtain();
        try {
            ctx.setServiceFQSID("bench-ling:com.bench.TestService");
            ctx.setMethodName("ping");
            ctx.setParameterTypeNames(new String[0]);
            ctx.setArgs(new Object[0]);
            ctx.governance().setAccessType(AccessType.EXECUTE);
            ctx.execution().setMode(InvocationExecutionMode.NORMAL);
            Object result = pipelineEngine.invoke(ctx);
            bh.consume(result);
        } finally {
            ctx.recycle();
        }
    }

    /**
     * 对独立热更新灵元执行一次完整热更新（部署新版本 → 卸载旧版本，多版本滚动）。
     * 每线程独立 lingId，避免并发热更新操作同一灵元导致状态机校验冲突。
     */
    private void hotUpdateOnce(long threadId) {
        String lingId = "hot-jitter-ling-" + threadId;
        String version = "1.0." + hotVersion.incrementAndGet();
        helper.deployLing(lingId, version);
        helper.undeployLing(lingId);
    }

    /**
     * 无热更新基线：纯 NORMAL 全链调用，作为抖动对比基准。
     */
    @Benchmark
    @OperationsPerInvocation(BATCH_SIZE)
    @Threads(8)
    public void steadyStateInvoke(Blackhole bh) {
        for (int i = 0; i < BATCH_SIZE; i++) {
            invokeOnce(bh);
        }
    }

    /**
     * 热更新进行中：在调用流中按可调间隔插入热更新，观测对吞吐与延迟的抖动影响。
     * <p>
     * 热更新在循环内按 {@link #HOT_UPDATE_INTERVAL} 间隔触发（对独立灵元，不与流量灵元冲突），
     * 相对「批次末尾单次触发」在调用流中的分布更均匀；间隔可经系统属性调整灵敏度。
     */
    @Benchmark
    @OperationsPerInvocation(BATCH_SIZE)
    @Threads(8)
    public void hotUpdateJitterInvoke(Blackhole bh) {
        for (int i = 0; i < BATCH_SIZE; i++) {
            invokeOnce(bh);
            if (HOT_UPDATE_INTERVAL > 0 && (i + 1) % HOT_UPDATE_INTERVAL == 0) {
                hotUpdateOnce(Thread.currentThread().getId());
            }
        }
    }
}
