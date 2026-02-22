package com.lingframe.starter.invoker;

import com.lingframe.core.spi.LingServiceInvoker;
import com.lingframe.core.ling.LingInstance;

import java.lang.reflect.Method;

import com.lingframe.starter.spi.LingInvocationFilter;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 支持滤链扩展的外骨骼服务调用器。
 * <p>
 * 它通过代理 {@link com.lingframe.core.invoker.DefaultLingServiceInvoker}
 * 或其他原生的微内核调用器，在此之上套上一层 AOP 的拦截网。
 * 用于实现例如全局限流、微服务 Trace ID 穿透等高级治理生态。
 */
public class FilterableLingServiceInvoker implements LingServiceInvoker {

    private final LingServiceInvoker delegate;
    private final List<LingInvocationFilter> filters;

    public FilterableLingServiceInvoker(LingServiceInvoker delegate, List<LingInvocationFilter> filters) {
        this.delegate = delegate;
        this.filters = filters != null ? filters.stream()
                .sorted(Comparator.comparingInt(LingInvocationFilter::getOrder))
                .collect(Collectors.toList()) : Collections.emptyList();
    }

    @Override
    public Object invoke(LingInstance instance, Object bean, Method method, Object[] args) throws Exception {
        if (filters.isEmpty()) {
            return delegate.invoke(instance, bean, method, args);
        }

        LingInvocationFilter.FilterChain chain = new LingInvocationFilter.FilterChain() {
            private int index = 0;

            @Override
            public Object proceed(LingInstance inst, Object b, Method m, Object[] a) throws Exception {
                if (index < filters.size()) {
                    LingInvocationFilter filter = filters.get(index++);
                    return filter.filter(inst, b, m, a, this);
                } else {
                    return delegate.invoke(inst, b, m, a);
                }
            }
        };

        return chain.proceed(instance, bean, method, args);
    }
}
