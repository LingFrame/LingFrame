package com.lingframe.core.pipeline;

import com.lingframe.core.ling.LingInstance;
import com.lingframe.core.spi.TrafficRouter;
import com.lingframe.core.util.VersionUtils;

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
        // 使用语义版本降序比较，min 取降序首位即最新版本。
        // 避免字符串字典序导致 "1.9.0" 排在 "1.10.0" 之前的错误。
        return candidates.stream()
                .min(Comparator.comparing(LingInstance::getVersion, VersionUtils::compareDescending))
                .orElseThrow(() -> new NoSuchElementException("No candidate instance found"));
    }
}
