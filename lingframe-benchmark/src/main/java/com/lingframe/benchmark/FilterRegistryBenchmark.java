package com.lingframe.benchmark;

import com.lingframe.api.security.PermissionService;
import com.lingframe.core.event.EventBus;
import com.lingframe.core.ling.DefaultLingRepository;
import com.lingframe.core.ling.InvokableMethodCache;
import com.lingframe.core.ling.LingRepository;
import com.lingframe.core.pipeline.FilterRegistry;
import com.lingframe.core.pipeline.LatestVersionPolicy;
import com.lingframe.core.security.DefaultPermissionService;
import com.lingframe.core.spi.LingInvocationFilter;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * FilterRegistry 链组装性能基准测试
 * <p>
 * 测量 FilterRegistry.getOrderedFilters() 的性能特征，
 * 包括首次组装（排序 + 契约校验）和后续缓存读取的开销。
 * <p>
 * 这是每次 Pipeline 调用的前置开销，getOrderedFilters() 的性能直接影响调用延迟。
 * <p>
 * 测试场景覆盖：
 * <ul>
 *   <li>getCachedFilterList —— 缓存命中路径：volatile read 一次即返回</li>
 *   <li>reassembleAfterInvalidation —— 缓存失效后重组装：排序 + 契约校验</li>
 * </ul>
 * <p>
 * 关键设计：
 * getCachedFilterList 使用 @State(Scope.Benchmark)，registry 全生命周期只组装一次，
 * 后续全部走 volatile read 缓存路径。
 * <p>
 * reassembleAfterInvalidation 使用 @State(Scope.Thread)，每次迭代重建 registry
 * 并添加动态过滤器触发缓存失效，测量的是"排序 + 契约校验"的真实开销，
 * 不引入反射调用的噪声。
 * <p>
 * 运行方式：
 * <pre>
 * mvn -pl lingframe-benchmark package -Pbenchmark -am -DskipTests
 * java -jar lingframe-benchmark/target/lingframe-benchmarks.jar FilterRegistryBenchmark
 * </pre>
 */
@BenchmarkMode({Mode.AverageTime, Mode.Throughput})
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 2)
@Fork(1)
public class FilterRegistryBenchmark {

    /**
     * 缓存命中路径的 State
     * <p>
     * registry 在 @Setup(Level.Trial) 中完整初始化一次，
     * 后续所有迭代都走 orderedCache volatile read 路径。
     */
    @State(Scope.Benchmark)
    public static class CachedState {
        public FilterRegistry registry;

        @Setup(Level.Trial)
        public void setup() {
            EventBus eventBus = new EventBus();
            PermissionService permissionService = new DefaultPermissionService(eventBus);
            InvokableMethodCache methodCache = new InvokableMethodCache();
            LingRepository lingRepository = new DefaultLingRepository();

            registry = new FilterRegistry(methodCache, permissionService);
            registry.initialize(lingRepository, new LatestVersionPolicy(), eventBus);

            // 预热缓存：首次 getOrderedFilters 触发组装，后续全部走缓存
            registry.getOrderedFilters();
        }
    }

    /**
     * 缓存失效后重组装的 State
     * <p>
     * 每次迭代重建 registry 并添加动态过滤器，确保 getOrderedFilters 必须重新组装。
     * 不使用反射，通过 addDynamicFilter 自然触发 invalidateCache()。
     * <p>
     * 每次迭代的测量范围：new FilterRegistry + initialize + addDynamicFilter + getOrderedFilters。
     * 其中前两步是固定开销（setup cost），可通过与 getCachedFilterList 的差值推算
     * "排序 + 契约校验"的增量开销。
     */
    @State(Scope.Thread)
    public static class ReassembleState {
        public FilterRegistry registry;

        /** 预创建动态过滤器，避免 @Setup(Level.Invocation) 中每次 new 匿名类引入分配噪声 */
        static final LingInvocationFilter DYNAMIC_FILTER = new LingInvocationFilter() {
            @Override
            public int getOrder() {
                return 500;
            }

            @Override
            public java.lang.Object doFilter(com.lingframe.core.pipeline.InvocationContext context,
                    com.lingframe.core.spi.LingFilterChain chain) throws Throwable {
                return chain.doFilter(context);
            }
        };

        @Setup(Level.Invocation)
        public void setup() {
            EventBus eventBus = new EventBus();
            PermissionService permissionService = new DefaultPermissionService(eventBus);
            InvokableMethodCache methodCache = new InvokableMethodCache();
            LingRepository lingRepository = new DefaultLingRepository();

            registry = new FilterRegistry(methodCache, permissionService);
            registry.initialize(lingRepository, new LatestVersionPolicy(), eventBus);
        }
    }

    /**
     * 测量缓存命中路径：getOrderedFilters() 读取已缓存的过滤器列表
     * <p>
     * 这是生产中每次 Pipeline 调用的实际路径。
     * orderedCache 是 volatile 引用，首次组装后后续读取只需一次 volatile read。
     */
    @Benchmark
    public List<LingInvocationFilter> getCachedFilterList(CachedState state, Blackhole bh) {
        List<LingInvocationFilter> filters = state.registry.getOrderedFilters();
        bh.consume(filters);
        return filters;
    }

    /**
     * 测量缓存失效后重新组装的开销
     * <p>
     * 每次迭代重建 registry 并添加动态过滤器，确保 getOrderedFilters 必须重新组装
     * （排序 + 契约校验）。addDynamicFilter 内部调用 invalidateCache()，
     * 使 orderedCache = null，下次 getOrderedFilters 必须重新计算。
     * <p>
     * 注意：此方法包含 registry 构造和初始化的固定开销。
     * "纯重组装"增量开销 = 此方法耗时 - registry 构造初始化耗时（需另建基线测量）。
     */
    @Benchmark
    public List<LingInvocationFilter> reassembleAfterInvalidation(ReassembleState state, Blackhole bh) {
        // addDynamicFilter 内部调用 invalidateCache()，使下次 getOrderedFilters 必须重组装
        state.registry.addDynamicFilter(ReassembleState.DYNAMIC_FILTER);
        List<LingInvocationFilter> filters = state.registry.getOrderedFilters();
        bh.consume(filters);
        return filters;
    }
}
