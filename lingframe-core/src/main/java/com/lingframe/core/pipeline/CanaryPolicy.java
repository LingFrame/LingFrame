package com.lingframe.core.pipeline;

import com.lingframe.core.kernel.InvocationContext;
import com.lingframe.core.ling.LingInstance;
import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.ThreadLocalRandom;

// 灰度：按百分比切分
public class CanaryPolicy implements RoutingPolicy {
    private final int canaryPercent;
    private final String canaryVersion;

    public CanaryPolicy(int canaryPercent, String canaryVersion) {
        this.canaryPercent = canaryPercent;
        this.canaryVersion = canaryVersion;
    }

    @Override
    public LingInstance select(InvocationContext ctx, List<LingInstance> candidates) {
        boolean toCanary = ThreadLocalRandom.current().nextInt(100) < canaryPercent;
        String targetVersion = toCanary ? canaryVersion : getCurrentStableVersion(candidates);
        return candidates.stream()
                .filter(i -> i.getVersion().equals(targetVersion))
                .findFirst()
                .orElseThrow(() -> new NoSuchElementException("No instance for version: " + targetVersion));
    }

    private String getCurrentStableVersion(List<LingInstance> candidates) {
        return candidates.stream()
                .filter(i -> !i.getVersion().equals(canaryVersion))
                .max(Comparator.comparing(LingInstance::getVersion))
                .map(LingInstance::getVersion)
                .orElseThrow(() -> new NoSuchElementException("No stable version found"));
    }
}
