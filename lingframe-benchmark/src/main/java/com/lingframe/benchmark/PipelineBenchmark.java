package com.lingframe.benchmark;

import com.lingframe.api.security.AccessType;
import com.lingframe.core.pipeline.*;
import com.lingframe.core.spi.LingFilterChain;
import com.lingframe.core.spi.LingInvocationFilter;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Pipeline 调用链性能基准测试
 * <p>
 * 测量 InvocationPipelineEngine 全链路（9 个 Filter）的延迟特征，
 * 这是生产环境中每次灵元服务调用必经的路径，是最关键的性能指标。
 * <p>
 * 测试场景覆盖：
 * <ul>
 * <li>happyPathPipeline —— 正常通过路径：灵元已注册、状态 ACTIVE、权限放行、终端执行</li>
 * <li>governOnlyPipeline —— GOVERN_ONLY 模式：治理校验后跳过终端调用</li>
 * <li>simulationPipeline —— SIMULATION 模式：走完全链路但不执行真实业务</li>
 * <li>contextObtainAndRecycle —— InvocationContext 对象池 obtain/recycle 开销</li>
 * <li>filterChainOverhead —— DefaultFilterChain 链式传递开销</li>
 * </ul>
 * <p>
 * 运行方式：
 * 
 * <pre>
 * mvn -pl lingframe-benchmark package -Pbenchmark -am -DskipTests
 * java -jar lingframe-benchmark/target/lingframe-benchmarks.jar PipelineBenchmark -f 3 -prof gc
 * </pre>
 */
@BenchmarkMode({ Mode.AverageTime, Mode.SampleTime })
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 3)
@Fork(value = 1, jvmArgs = { "-Xms2g", "-Xmx2g", "-XX:+UseG1GC", "-XX:+AlwaysPreTouch", "-Dorg.slf4j.simpleLogger.defaultLogLevel=warn" })
@State(Scope.Benchmark)
public class PipelineBenchmark {

    private InvocationPipelineEngine pipelineEngine;

    /** 预创建的空过滤器实例列表，用于测量链式传递开销 */
    private List<LingInvocationFilter> chainFilters;

    @Setup(Level.Trial)
    public void setup() {
        BenchmarkDeploymentHelper helper = new BenchmarkDeploymentHelper();

        // 通过双层状态机正确部署灵元：deploy() 协调 RuntimeCoordinator + InstanceCoordinator
        helper.deployLing("bench-ling", "1.0.0");

        this.pipelineEngine = helper.getPipelineEngine();

        // 预创建 3 个传递型过滤器：每个都调用 chain.doFilter()，真正测量链式传递开销
        chainFilters = new java.util.ArrayList<LingInvocationFilter>();
        for (int i = 0; i < 3; i++) {
            chainFilters.add(new LingInvocationFilter() {
                @Override
                public int getOrder() {
                    return 0;
                }

                @Override
                public Object doFilter(InvocationContext context, LingFilterChain chain) throws Throwable {
                    return chain.doFilter(context);
                }
            });
        }
        // 末尾加一个终止过滤器，返回结果
        chainFilters.add(new LingInvocationFilter() {
            @Override
            public int getOrder() {
                return 0;
            }

            @Override
            public Object doFilter(InvocationContext context, LingFilterChain chain) {
                return "chain-end";
            }
        });
    }

    /**
     * 测量正常通过路径的 Pipeline 全链路延迟
     * <p>
     * 灵元已注册且状态为 ACTIVE，权限放行，终端执行。
     * 这是生产环境中最常见的调用路径，也是最有说服力的性能指标。
     * 使用 NORMAL 模式：治理链路 + 终端调用全走通。
     */
    @Benchmark
    public void happyPathPipeline(Blackhole bh) {
        InvocationContext ctx = InvocationContext.obtain();
        try {
            ctx.setServiceFQSID("bench-ling:com.bench.TestService");
            ctx.setMethodName("ping");
            ctx.setParameterTypeNames(new String[0]);
            ctx.setArgs(new java.lang.Object[0]);
            ctx.setAccessType(AccessType.EXECUTE);
            ctx.setExecutionMode(InvocationExecutionMode.NORMAL);
            Object result = pipelineEngine.invoke(ctx);
            bh.consume(result);
        } finally {
            ctx.recycle();
        }
    }

    /**
     * 测量 GOVERN_ONLY 模式下 Pipeline 全链路延迟
     * <p>
     * 灵核 Bean 方法拦截场景：Pipeline 完成治理校验后跳过终端调用。
     */
    @Benchmark
    public void governOnlyPipeline(Blackhole bh) {
        InvocationContext ctx = InvocationContext.obtain();
        try {
            ctx.setServiceFQSID("bench-ling:com.bench.TestService");
            ctx.setMethodName("ping");
            ctx.setParameterTypeNames(new String[0]);
            ctx.setArgs(new java.lang.Object[0]);
            ctx.setAccessType(AccessType.EXECUTE);
            ctx.setExecutionMode(InvocationExecutionMode.GOVERN_ONLY);
            Object result = pipelineEngine.invoke(ctx);
            bh.consume(result);
        } finally {
            ctx.recycle();
        }
    }

    /**
     * 测量 SIMULATION 模式下 Pipeline 全链路延迟
     * <p>
     * Dashboard 模拟调用：Pipeline 走完全链路但不执行真实业务。
     */
    @Benchmark
    public void simulationPipeline(Blackhole bh) {
        InvocationContext ctx = InvocationContext.obtain();
        try {
            ctx.setServiceFQSID("bench-ling:com.bench.TestService");
            ctx.setMethodName("ping");
            ctx.setParameterTypeNames(new String[0]);
            ctx.setArgs(new java.lang.Object[0]);
            ctx.setAccessType(AccessType.EXECUTE);
            ctx.setExecutionMode(InvocationExecutionMode.SIMULATION);
            Object result = pipelineEngine.invoke(ctx);
            bh.consume(result);
        } finally {
            ctx.recycle();
        }
    }

    /**
     * 测量 InvocationContext 对象池 obtain/recycle 的开销
     * <p>
     * 这是每次 Pipeline 调用的前置/后置开销基线。
     */
    @Benchmark
    public void contextObtainAndRecycle(Blackhole bh) {
        InvocationContext ctx = InvocationContext.obtain();
        bh.consume(ctx);
        ctx.recycle();
    }

    /**
     * 测量 DefaultFilterChain 链式传递开销
     * <p>
     * 使用 3 个传递型过滤器（每个调用 chain.doFilter()）+ 1 个终止过滤器，
     * 测量链式调用本身的方法调用开销，为 Pipeline 延迟分析提供"纯框架开销"基线。
     */
    @Benchmark
    public void filterChainOverhead(Blackhole bh) throws Throwable {
        InvocationContext ctx = InvocationContext.obtain();
        try {
            LingFilterChain chain = new DefaultFilterChain(chainFilters);
            Object result = chain.doFilter(ctx);
            bh.consume(result);
        } finally {
            ctx.recycle();
        }
    }
}
