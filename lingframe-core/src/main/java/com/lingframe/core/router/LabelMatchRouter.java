package com.lingframe.core.router;

import com.lingframe.core.kernel.InvocationContext;
import com.lingframe.core.plugin.PluginInstance;
import com.lingframe.core.spi.TrafficRouter;
import lombok.extern.slf4j.Slf4j;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Slf4j
public class LabelMatchRouter implements TrafficRouter {

    @Override
    public PluginInstance route(List<PluginInstance> candidates, InvocationContext context) {
        if (candidates == null || candidates.isEmpty()) return null;

        Map<String, String> requestLabels = (context != null) ? context.getLabels() : null;

        // 如果没有请求标签，或者候选只有一个，直接返回第一个（通常是 Active）
        if (requestLabels == null || requestLabels.isEmpty()) {
            return candidates.getFirst();
        }

        // 标签打分逻辑
        return candidates.stream()
                .map(inst -> new ScoredInstance(inst, calculateScore(inst.getLabels(), requestLabels)))
                .filter(si -> si.score >= 0) // 过滤掉不匹配的 (score = -1)
                .max(Comparator.comparingInt(si -> si.score))
                .map(si -> si.instance)
                .orElse(candidates.getFirst());
    }

    private int calculateScore(Map<String, String> instLabels, Map<String, String> reqLabels) {
        int score = 0;
        for (Map.Entry<String, String> entry : reqLabels.entrySet()) {
            String val = instLabels.get(entry.getKey());
            // 完全匹配加分
            if (Objects.equals(val, entry.getValue())) {
                score += 10;
            }
            // 实例有此标签但值不匹配，视为不兼容
            else if (val != null) {
                return -1;
            }
        }
        return score;
    }

    private record ScoredInstance(PluginInstance instance, int score) {
    }
}