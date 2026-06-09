package com.lingframe.benchmark;

import com.lingframe.core.pipeline.InvocationExecutionMode;
import com.lingframe.api.security.AccessType;
import com.lingframe.api.exception.LingInvocationException;
import com.lingframe.core.pipeline.InvocationContext;
import com.lingframe.core.pipeline.InvocationPipelineEngine;
import com.lingframe.core.pipeline.FilterPhase;
import com.lingframe.core.spi.LingFilterChain;
import com.lingframe.core.spi.LingInvocationFilter;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

/**
 * 悲观异常路径性能基准测试与三层对照组分析
 * <p>
 * 为完全剥离框架治理拦截异常在虚拟机层面的增量成本，本测试设计了三层对照：
 * 1. 第一层（纯跳转机制成本）：通过覆写 fillInStackTrace() 并返回 this 的静态常量异常 PRE_ALLOCATED 测定裸 JVM 的 throw-catch 开销；
 * 2. 第二层（栈回溯成本）：测试普通 new Exception() 的 throw-catch 耗时，量化 fillInStackTrace() 对主内存进行栈帧遍历填充的昂贵开销；
 * 3. 第三层（框架悲观拦截增量成本）：测试由于限流或越权拦截触发 Pipeline 抛出 LingInvocationException 的耗时。
 * <p>
 * 增量成本 = 第三层 - 第二层。
 * <p>
 * 运行方式：
 * <pre>
 * mvn -pl lingframe-benchmark package -Pbenchmark -am -DskipTests
 * java -jar lingframe-benchmark/target/lingframe-benchmarks.jar PipelineExceptionBenchmark -f 3
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
public class PipelineExceptionBenchmark {

    private static final Exception PRE_ALLOCATED = new Exception("baseline") {
        @Override
        public synchronized Throwable fillInStackTrace() {
            return this;
        }
    };

    private InvocationPipelineEngine pipelineEngine;
    private BenchmarkDeploymentHelper helper;
    private LingInvocationFilter exceptionFilter;

    @Setup(Level.Trial)
    public void setup() {
        helper = new BenchmarkDeploymentHelper();
        helper.deployLing("bench-ling", "1.0.0");
        pipelineEngine = helper.getPipelineEngine();

        // 插入一个总是抛出 SECURITY_REJECTED 异常的 Filter，模拟被治理拦截的悲观路径
        exceptionFilter = new LingInvocationFilter() {
            @Override
            public int getOrder() {
                return FilterPhase.GOVERNANCE + 10;
            }

            @Override
            public Object doFilter(InvocationContext context, LingFilterChain chain) throws Throwable {
                throw new LingInvocationException("bench-ling", LingInvocationException.ErrorKind.SECURITY_REJECTED);
            }
        };

        helper.getFilterRegistry().addDynamicFilter(exceptionFilter);
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        if (helper != null && exceptionFilter != null) {
            helper.getFilterRegistry().removeDynamicFilter(exceptionFilter);
        }
    }

    /**
     * 第一层对照组：测试预分配异常的 throw-catch 机制开销 (无栈回溯)
       */
    @Benchmark
    public void baselinePreAllocated(Blackhole bh) {
        try {
            throw PRE_ALLOCATED;
        } catch (Exception e) {
            bh.consume(e);
        }
    }

    /**
     * 第二层对照组：测试普通新建异常的 throw-catch 开销 (包含 JVM 物理栈回溯)
     */
    @Benchmark
    public void baselineNormalException(Blackhole bh) {
        try {
            throw new Exception("baseline");
        } catch (Exception e) {
            bh.consume(e);
        }
    }

    /**
     * 第三层测试：测试真实 Pipeline 调用时因治理拦截触发抛出 LingInvocationException 的完整延迟
     */
    @Benchmark
    public void pipelinePessimisticException(Blackhole bh) {
        InvocationContext ctx = InvocationContext.obtain();
        try {
            ctx.setServiceFQSID("bench-ling:com.bench.TestService");
            ctx.setMethodName("ping");
            ctx.setParameterTypeNames(new String[0]);
            ctx.setArgs(new Object[0]);
            ctx.setAccessType(AccessType.EXECUTE);
            ctx.setExecutionMode(InvocationExecutionMode.NORMAL);
            pipelineEngine.invoke(ctx);
        } catch (Throwable e) {
            bh.consume(e);
        } finally {
            ctx.recycle();
        }
    }
}
