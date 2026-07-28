package com.lingframe.core.routing;

import com.lingframe.core.ling.LingInstance;
import com.lingframe.core.pipeline.InvocationContext;
import com.lingframe.core.spi.TrafficRouter;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 标签匹配路由器。
 * <p>
 * 根据请求标签与实例标签的匹配度选择实例。
 * 无请求标签时退化为权重路由。
 */
@Slf4j
public class LabelMatchRouter implements TrafficRouter {

    @Override
    public LingInstance route(List<LingInstance> candidates, InvocationContext context) {
        if (candidates == null || candidates.isEmpty())
            return null;

        // 防御性快照：过滤掉 definition 为 null 的实例，避免后续 getWeight/calculateScore 触发 NPE
        List<LingInstance> validCandidates = new ArrayList<>(candidates.size());
        for (LingInstance inst : candidates) {
            if (inst != null && inst.getDefinition() != null) {
                validCandidates.add(inst);
            }
        }
        if (validCandidates.isEmpty()) {
            return null;
        }

        Map<String, String> requestLabels = (context != null) ? context.getLabels() : null;

        // 如果没有请求标签
        if (requestLabels == null || requestLabels.isEmpty()) {
            if (validCandidates.size() == 1) {
                return validCandidates.get(0);
            }
            // 尝试权重路由
            return doWeightedRoute(validCandidates);
        }

        // 标签打分逻辑：先过滤不兼容候选（score = -1），再按最高分选实例
        // 若所有候选均与请求标签冲突（过滤后为空），返回 null 让调用方当路由失败处理，
        // 严禁回退到 validCandidates.get(0)——那是被评分逻辑显式标记为不兼容的实例
        List<ScoredInstance> compatible = validCandidates.stream()
                .map(inst -> new ScoredInstance(inst, calculateScore(inst.getLabels(), requestLabels)))
                .filter(si -> si.getScore() >= 0)
                .collect(java.util.stream.Collectors.toList());
        if (compatible.isEmpty()) {
            return null;
        }
        return compatible.stream()
                .max(Comparator.comparingInt(si -> si.getScore()))
                .map(ScoredInstance::getInstance)
                .orElse(compatible.get(0).getInstance());
    }

    private LingInstance doWeightedRoute(List<LingInstance> candidates) {
        int totalWeight = 0;
        int[] weights = new int[candidates.size()];

        for (int i = 0; i < candidates.size(); i++) {
            LingInstance inst = candidates.get(i);
            int weight = getWeight(inst);
            weights[i] = weight;
            totalWeight += weight;
        }

        if (totalWeight <= 0) {
            return candidates.get(0);
        }

        int random = ThreadLocalRandom.current().nextInt(totalWeight);
        int current = 0;
        for (int i = 0; i < candidates.size(); i++) {
            current += weights[i];
            if (random < current) {
                return candidates.get(i);
            }
        }
        return candidates.get(0);
    }

    private int getWeight(LingInstance instance) {
        // 默认权重 100
        int defaultWeight = 100;

        // 对 definition/properties 为 null 的实例防御性返回默认权重
        if (instance.getDefinition() == null || instance.getDefinition().getProperties() == null) {
            return defaultWeight;
        }

        // 尝试从 properties 获取
        Object val = instance.getDefinition().getProperties().get("trafficWeight");
        if (val != null) {
            try {
                return Integer.parseInt(val.toString());
            } catch (NumberFormatException e) {
                log.warn("Invalid trafficWeight for ling {}: {}", instance.getVersion(), val);
            }
        }
        return defaultWeight;
    }

    private int calculateScore(Map<String, String> instLabels, Map<String, String> reqLabels) {
        // 实例无标签时：请求标签均无法匹配，但不视为冲突，返回 0 分
        if (instLabels == null) {
            return 0;
        }
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

    @Getter
    public class ScoredInstance {
        LingInstance instance;
        int score;

        ScoredInstance(LingInstance instance, int score) {
            this.instance = instance;
            this.score = score;
        }
    }
}
