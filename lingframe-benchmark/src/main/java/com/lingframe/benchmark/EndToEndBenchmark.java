package com.lingframe.benchmark;

import com.lingframe.core.pipeline.InvocationExecutionMode;
import com.lingframe.api.security.AccessType;
import com.lingframe.core.pipeline.InvocationContext;
import com.lingframe.core.pipeline.InvocationPipelineEngine;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

/**
 * 稳态 E2E 全链路调用性能基准测试
 * <p>
 * 测量在灵珑底座完全激活、稳态运行场景下的端到端调用延迟与并发吞吐。
 * 为避免 JMH 单次迭代调用的物理测量噪声，本测试在 benchmark 方法内部执行 100 次循环调用，
 * 并修饰 {@link OperationsPerInvocation @OperationsPerInvocation(100)}。
 * 这样 JMH 在换算时能输出高精度的单次操作（Operation）均值。
 * <p>
 * 运行方式：
 * <pre>
 * mvn -pl lingframe-benchmark package -Pbenchmark -am -DskipTests
 * java -jar lingframe-benchmark/target/lingframe-benchmarks.jar EndToEndBenchmark -f 3
 * </pre>
 */
@BenchmarkMode({ Mode.Throughput, Mode.AverageTime })
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
public class EndToEndBenchmark {

    private InvocationPipelineEngine pipelineEngine;
    private BenchmarkDeploymentHelper helper;

    @Setup(Level.Trial)
    public void setup() {
        helper = new BenchmarkDeploymentHelper();
        helper.deployLing("bench-ling", "1.0.0");
        pipelineEngine = helper.getPipelineEngine();
    }

    /**
     * 单线程稳态端到端调用基线
     */
    @Benchmark
    @OperationsPerInvocation(100)
    @Threads(1)
    public void steadyStateInvoke_1Thread(Blackhole bh) {
        for (int i = 0; i < 100; i++) {
            InvocationContext ctx = InvocationContext.obtain();
            try {
                ctx.setServiceFQSID("bench-ling:com.bench.TestService");
                ctx.setMethodName("ping");
                ctx.setParameterTypeNames(new String[0]);
                ctx.setArgs(new Object[0]);
                ctx.setAccessType(AccessType.EXECUTE);
                ctx.setExecutionMode(InvocationExecutionMode.NORMAL);
                Object result = pipelineEngine.invoke(ctx);
                bh.consume(result);
            } finally {
                ctx.recycle();
            }
        }
    }

    /**
     * 4 线程并发稳态端到端调用
     */
    @Benchmark
    @OperationsPerInvocation(100)
    @Threads(4)
    public void steadyStateInvoke_4Threads(Blackhole bh) {
        for (int i = 0; i < 100; i++) {
            InvocationContext ctx = InvocationContext.obtain();
            try {
                ctx.setServiceFQSID("bench-ling:com.bench.TestService");
                ctx.setMethodName("ping");
                ctx.setParameterTypeNames(new String[0]);
                ctx.setArgs(new Object[0]);
                ctx.setAccessType(AccessType.EXECUTE);
                ctx.setExecutionMode(InvocationExecutionMode.NORMAL);
                Object result = pipelineEngine.invoke(ctx);
                bh.consume(result);
            } finally {
                ctx.recycle();
            }
        }
    }

    /**
     * 8 线程并发稳态端到端调用
     */
    @Benchmark
    @OperationsPerInvocation(100)
    @Threads(8)
    public void steadyStateInvoke_8Threads(Blackhole bh) {
        for (int i = 0; i < 100; i++) {
            InvocationContext ctx = InvocationContext.obtain();
            try {
                ctx.setServiceFQSID("bench-ling:com.bench.TestService");
                ctx.setMethodName("ping");
                ctx.setParameterTypeNames(new String[0]);
                ctx.setArgs(new Object[0]);
                ctx.setAccessType(AccessType.EXECUTE);
                ctx.setExecutionMode(InvocationExecutionMode.NORMAL);
                Object result = pipelineEngine.invoke(ctx);
                bh.consume(result);
            } finally {
                ctx.recycle();
            }
        }
    }
}
