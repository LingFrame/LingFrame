package com.lingframe.core.pipeline;

import com.lingframe.core.kernel.InvocationContext;
import com.lingframe.core.ling.LingInstance;
import com.lingframe.core.spi.TrafficRouter;

import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * 默认路由策略：全部流量走最新 READY 版本。
 * 当 TrafficRouter 未被显式配置时，作为 fallback 使用。
 */
public class LatestVersionPolicy implements TrafficRouter {
    @Override
    public LingInstance route(List<LingInstance> candidates, InvocationContext context) {
        return candidates.stream()
                .max(Comparator.comparing(LingInstance::getVersion))
                .orElseThrow(() -> new NoSuchElementException("No candidate instance found"));
    }
}
