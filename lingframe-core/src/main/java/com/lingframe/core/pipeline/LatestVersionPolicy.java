package com.lingframe.core.pipeline;

import com.lingframe.core.kernel.InvocationContext;
import com.lingframe.core.ling.LingInstance;
import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;

// 默认：全部流量走最新 READY 版本
public class LatestVersionPolicy implements RoutingPolicy {
    @Override
    public LingInstance select(InvocationContext ctx, List<LingInstance> candidates) {
        return candidates.stream()
                .max(Comparator.comparing(LingInstance::getVersion))
                .orElseThrow(() -> new NoSuchElementException("No candidate instance found"));
    }
}
