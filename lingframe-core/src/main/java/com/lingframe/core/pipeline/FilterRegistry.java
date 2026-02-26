package com.lingframe.core.pipeline;

import com.lingframe.core.spi.LingInvocationFilter;
import java.util.*;

public class FilterRegistry {
    private final List<LingInvocationFilter> builtinFilters = new ArrayList<>();
    private final List<LingInvocationFilter> spiFilters = new ArrayList<>();

    public void initialize(ClassLoader hostClassLoader) {
        // 1. 注册内置 Filter
        builtinFilters.addAll(Arrays.asList(
                new TrafficMetricsFilter(),
                new MacroStateGuardFilter(),
                new CanaryRoutingFilter(),
                new ResilienceGovernanceFilter(),
                new ContextIsolationFilter(),
                new TerminalInvokerFilter()));

        // 2. 从宿主 ClassLoader 加载 SPI 扩展
        for (LingInvocationFilter filter : ServiceLoader.load(LingInvocationFilter.class, hostClassLoader)) {
            spiFilters.add(filter);
        }
    }

    // 3. 允许外部框架（如 Spring Starter）动态注入托管的 Filter
    public void addDynamicFilter(LingInvocationFilter filter) {
        this.spiFilters.add(filter);
    }

    public List<LingInvocationFilter> getOrderedFilters() {
        List<LingInvocationFilter> all = new ArrayList<>();
        all.addAll(builtinFilters);
        all.addAll(spiFilters);
        all.sort(Comparator.comparingInt(LingInvocationFilter::getOrder));
        return Collections.unmodifiableList(all);
    }
}
