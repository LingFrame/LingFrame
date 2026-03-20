package com.lingframe.starter.web;

import com.lingframe.core.ling.LingInstance;
import com.lingframe.core.ling.LingRuntime;

/**
 * 供治理与分发共享的不可变路由解析结果。
 */
public class WebRouteResolution {

    private final String routeKey;
    private final WebInterfaceMetadata metadata;
    private final LingRuntime runtime;
    private final LingInstance targetInstance;

    public WebRouteResolution(String routeKey,
                              WebInterfaceMetadata metadata,
                              LingRuntime runtime,
                              LingInstance targetInstance) {
        this.routeKey = routeKey;
        this.metadata = metadata;
        this.runtime = runtime;
        this.targetInstance = targetInstance;
    }

    public String getRouteKey() {
        return routeKey;
    }

    public WebInterfaceMetadata getMetadata() {
        return metadata;
    }

    public LingRuntime getRuntime() {
        return runtime;
    }

    public LingInstance getTargetInstance() {
        return targetInstance;
    }

    public boolean matchesRouteKey(String expectedRouteKey) {
        return routeKey != null && routeKey.equals(expectedRouteKey);
    }
}
