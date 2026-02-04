package com.lingframe.core.router;

import com.lingframe.core.kernel.InvocationContext;
import com.lingframe.core.plugin.PluginInstance;
import com.lingframe.core.spi.TrafficRouter;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Slf4j
public class LabelMatchRouter implements TrafficRouter {

    @Override
    public PluginInstance route(List<PluginInstance> candidates, InvocationContext context) {
        if (candidates == null || candidates.isEmpty())
            return null;

        Map<String, String> requestLabels = (context != null) ? context.getLabels() : null;

        // 如果没有请求标签
        if (requestLabels == null || requestLabels.isEmpty()) {
            if (candidates.size() == 1) {
                return candidates.get(0);
            }
            // 尝试权重路由
            return doWeightedRoute(candidates);
        }

        // 标签打分逻辑
        return candidates.stream()
                .map(inst -> new ScoredInstance(inst, calculateScore(inst.getLabels(), requestLabels)))
                .filter(si -> si.score >= 0) // 过滤掉不匹配的 (score = -1)
                .max(Comparator.comparingInt(si -> si.score))
                .map(si -> si.instance)
                .orElse(candidates.get(0));
    }

    private PluginInstance doWeightedRoute(List<PluginInstance> candidates) {
        int totalWeight = 0;
        int[] weights = new int[candidates.size()];

        for (int i = 0; i < candidates.size(); i++) {
            PluginInstance inst = candidates.get(i);
            int weight = getWeight(inst);
            weights[i] = weight;
            totalWeight += weight;
        }

        if (totalWeight <= 0) {
            return candidates.get(0);
        }

        int random = java.util.concurrent.ThreadLocalRandom.current().nextInt(totalWeight);
        int current = 0;
        for (int i = 0; i < candidates.size(); i++) {
            current += weights[i];
            if (random < current) {
                return candidates.get(i);
            }
        }
        return candidates.get(0);
    }

    private int getWeight(PluginInstance instance) {
        // 默认权重 100
        int defaultWeight = 100;

        // 尝试从 properties 获取
        if (instance.getDefinition().getProperties() != null) {
            Object val = instance.getDefinition().getProperties().get("trafficWeight");
            if (val != null) {
                try {
                    return Integer.parseInt(val.toString());
                } catch (NumberFormatException e) {
                    log.warn("Invalid trafficWeight for plugin {}: {}", instance.getVersion(), val);
                }
            }
        }
        return defaultWeight;
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

    @Value
    @Getter(AccessLevel.NONE)  // 不生成标准 getter
    public class ScoredInstance {
        PluginInstance instance;
        int score;

        // Record 风格的访问器
        public PluginInstance instance() { return instance; }
        public int score() { return score; }
    }
}