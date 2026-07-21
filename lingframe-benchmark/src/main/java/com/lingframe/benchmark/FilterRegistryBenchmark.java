package com.lingframe.benchmark;

import com.lingframe.api.security.PermissionService;
import com.lingframe.core.config.LingFrameConfig;
import com.lingframe.core.event.EventBus;
import com.lingframe.core.ling.DefaultLingRepository;
import com.lingframe.core.ling.InvokableMethodCache;
import com.lingframe.core.ling.LingRepository;
import com.lingframe.core.pipeline.FilterRegistry;
import com.lingframe.core.pipeline.FilterRegistryConfig;
import com.lingframe.core.pipeline.InvocationContext;
import com.lingframe.core.pipeline.LatestVersionPolicy;
import com.lingframe.core.security.DefaultPermissionService;
import com.lingframe.core.spi.LingFilterChain;
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
 * reassembleAfterInvalidation 使用 @State(Scope.Benchmark)，在 @Setup(Level.Trial) 中
 * 构建一次 FilterRegistry，benchmark 方法内通过 add/get/remove 循环触发缓存失效和重组装，
 * 避免 @Setup(Level.Invocation) 带来的测量噪声。
 * <p>
 * 运行方式：
 * <pre>
 * mvn -pl lingframe-benchmark package -Pbenchmark -am -DskipTests
 * java -jar lingframe-benchmark/target/lingframe-benchmarks.jar FilterRegistryBenchmark -f 3 -prof gc
 * </pre>
 */
@BenchmarkMode({Mode.AverageTime, Mode.Throughput})
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 2)
@Fork(value = 1, jvmArgs = {"-Xms2g", "-Xmx2g", "-XX:+UseG1GC", "-XX:+AlwaysPreTouch", "-Dorg.slf4j.simpleLogger.defaultLogLevel=warn"})
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
            PermissionService permissionService = new DefaultPermissionService(eventBus, LingFrameConfig.current());
            InvokableMethodCache methodCache = new InvokableMethodCache();
            LingRepository lingRepository = new DefaultLingRepository();

            registry = new FilterRegistry(FilterRegistryConfig.builder()
                    .methodCache(methodCache)
                    .permissionService(permissionService)
                    .lingRepository(lingRepository)
                    .trafficRouter(new LatestVersionPolicy())
                    .eventBus(eventBus)
                    .build());

            // 预热缓存：首次 getOrderedFilters 触发组装，后续全部走缓存
            registry.getOrderedFilters();
        }
    }

    /**
     * 缓存失效后重组装的 State
     * <p>
     * 在 @Setup(Level.Trial) 中构建一次 FilterRegistry，
     * benchmark 方法内通过 addDynamicFilter → getOrderedFilters → removeDynamicFilter
     * 循环实现"每次迭代都走重组装路径"的效果。
     * <p>
     * 相比旧版 @Setup(Level.Invocation) 每次重建整个 EventBus + FilterRegistry 的方案：
     * 1. 消除了 Level.Invocation 的测量框架噪声
     * 2. 排除了对象构造开销（只测纯重组装）
     * 3. 预创建静态过滤器实例，避免匿名类分配噪声
     */
    @State(Scope.Benchmark)
    public static class ReassembleState {
        public FilterRegistry registry;

        /** 预创建动态过滤器，避免每次迭代 new 匿名类引入分配噪声 */
        static final LingInvocationFilter DYNAMIC_FILTER = new LingInvocationFilter() {
            @Override
            public int getOrder() {
                return 500;
            }

            @Override
            public Object doFilter(InvocationContext context,
                            LingFilterChain chain) throws Throwable {
                return chain.doFilter(context);
            }
        };

        @Setup(Level.Trial)
        public void setup() {
            EventBus eventBus = new EventBus();
            PermissionService permissionService = new DefaultPermissionService(eventBus, LingFrameConfig.current());
            InvokableMethodCache methodCache = new InvokableMethodCache();
            LingRepository lingRepository = new DefaultLingRepository();

            registry = new FilterRegistry(FilterRegistryConfig.builder()
                    .methodCache(methodCache)
                    .permissionService(permissionService)
                    .lingRepository(lingRepository)
                    .trafficRouter(new LatestVersionPolicy())
                    .eventBus(eventBus)
                    .build());
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
     * 每次迭代：addDynamicFilter（触发 invalidateCache） → getOrderedFilters（重组装）
     * → removeDynamicFilter（触发 invalidateCache，为下次迭代准备）。
     * <p>
     * 报告的延迟包含 add + get + remove 三步操作。
     * 其中 add 和 remove 各含一次 ArrayList 操作 + volatile write（invalidateCache），
     * get 包含排序 + 契约校验。纯重组装开销 ≈ 结果 - 2 × (ArrayList 操作 + volatile write)。
     */
    @Benchmark
    public List<LingInvocationFilter> reassembleAfterInvalidation(ReassembleState state, Blackhole bh) {
        // addDynamicFilter 内部调用 invalidateCache()
        state.registry.addDynamicFilter(ReassembleState.DYNAMIC_FILTER);
        List<LingInvocationFilter> filters = state.registry.getOrderedFilters();
        bh.consume(filters);
        // 移除过滤器恢复初始状态，removeDynamicFilter 内部也调用 invalidateCache()
        state.registry.removeDynamicFilter(ReassembleState.DYNAMIC_FILTER);
        return filters;
    }
}
