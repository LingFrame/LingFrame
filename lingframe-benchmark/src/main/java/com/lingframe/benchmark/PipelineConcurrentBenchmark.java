package com.lingframe.benchmark;

import com.lingframe.api.security.AccessType;
import com.lingframe.core.pipeline.*;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

/**
 * Pipeline 并发性能基准测试
 * <p>
 * 测量多线程下 InvocationPipelineEngine 的吞吐量和延迟特征，
 * 验证 InvocationContext ThreadLocal 对象池、ConcurrentHashMap 查找等
 * 在并发调用时是否存在争用瓶颈。
 * <p>
 * 运行方式：
 * 
 * <pre>
 * mvn -pl lingframe-benchmark package -Pbenchmark -am -DskipTests
 * java -jar lingframe-benchmark/target/lingframe-benchmarks.jar PipelineConcurrentBenchmark
 * </pre>
 */
@BenchmarkMode({ Mode.Throughput, Mode.AverageTime })
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
public class PipelineConcurrentBenchmark {

    private InvocationPipelineEngine pipelineEngine;

    @Setup(Level.Trial)
    public void setup() {
        BenchmarkDeploymentHelper helper = new BenchmarkDeploymentHelper();
        helper.deployLing("bench-ling", "1.0.0");
        this.pipelineEngine = helper.getPipelineEngine();
    }

    /**
     * 单线程 NORMAL 吞吐量基线
     * <p>
     * NORMAL 模式走完全链路（治理 + 终端调用），是最接近生产场景的路径。
     */
    @Benchmark
    @Threads(1)
    public void normal_1Thread(Blackhole bh) {
        invokeNormal(bh);
    }

    /**
     * 4 线程并发 NORMAL 吞吐量
     */
    @Benchmark
    @Threads(4)
    public void normal_4Threads(Blackhole bh) {
        invokeNormal(bh);
    }

    /**
     * 8 线程并发 NORMAL 吞吐量
     */
    @Benchmark
    @Threads(8)
    public void normal_8Threads(Blackhole bh) {
        invokeNormal(bh);
    }

    /**
     * 单线程 GOVERN_ONLY 吞吐量基线
     */
    @Benchmark
    @Threads(1)
    public void governOnly_1Thread(Blackhole bh) {
        invokeGovernOnly(bh);
    }

    /**
     * 4 线程并发 GOVERN_ONLY 吞吐量
     */
    @Benchmark
    @Threads(4)
    public void governOnly_4Threads(Blackhole bh) {
        invokeGovernOnly(bh);
    }

    /**
     * 8 线程并发 GOVERN_ONLY 吞吐量
     */
    @Benchmark
    @Threads(8)
    public void governOnly_8Threads(Blackhole bh) {
        invokeGovernOnly(bh);
    }

    /**
     * 单线程 SIMULATION 吞吐量基线
     */
    @Benchmark
    @Threads(1)
    public void simulation_1Thread(Blackhole bh) {
        invokeSimulation(bh);
    }

    /**
     * 4 线程并发 SIMULATION 吞吐量
     */
    @Benchmark
    @Threads(4)
    public void simulation_4Threads(Blackhole bh) {
        invokeSimulation(bh);
    }

    /**
     * 8 线程并发 SIMULATION 吞吐量
     */
    @Benchmark
    @Threads(8)
    public void simulation_8Threads(Blackhole bh) {
        invokeSimulation(bh);
    }

    private void invokeNormal(Blackhole bh) {
        InvocationContext ctx = InvocationContext.obtain();
        try {
            ctx.setServiceFQSID("bench-ling:com.bench.TestService");
            ctx.setMethodName("ping");
            ctx.setParameterTypeNames(new String[0]);
            ctx.setArgs(new Object[0]);
            ctx.governance().setAccessType(AccessType.EXECUTE);
            ctx.execution().setMode(InvocationExecutionMode.NORMAL);
            bh.consume(pipelineEngine.invoke(ctx));
        } finally {
            ctx.recycle();
        }
    }

    private void invokeGovernOnly(Blackhole bh) {
        InvocationContext ctx = InvocationContext.obtain();
        try {
            ctx.setServiceFQSID("bench-ling:com.bench.TestService");
            ctx.setMethodName("ping");
            ctx.setParameterTypeNames(new String[0]);
            ctx.setArgs(new Object[0]);
            ctx.governance().setAccessType(AccessType.EXECUTE);
            ctx.execution().setMode(InvocationExecutionMode.GOVERN_ONLY);
            bh.consume(pipelineEngine.invoke(ctx));
        } finally {
            ctx.recycle();
        }
    }

    private void invokeSimulation(Blackhole bh) {
        InvocationContext ctx = InvocationContext.obtain();
        try {
            ctx.setServiceFQSID("bench-ling:com.bench.TestService");
            ctx.setMethodName("ping");
            ctx.setParameterTypeNames(new String[0]);
            ctx.setArgs(new Object[0]);
            ctx.governance().setAccessType(AccessType.EXECUTE);
            ctx.execution().setMode(InvocationExecutionMode.SIMULATION);
            bh.consume(pipelineEngine.invoke(ctx));
        } finally {
            ctx.recycle();
        }
    }
}
