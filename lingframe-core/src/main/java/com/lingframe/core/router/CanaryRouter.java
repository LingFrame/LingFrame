package com.lingframe.core.router;

import com.lingframe.core.pipeline.InvocationContext;
import com.lingframe.core.ling.LingInstance;
import com.lingframe.core.spi.CanaryConfigurable;
import com.lingframe.core.spi.TrafficRouter;
import com.lingframe.api.exception.InvalidArgumentException;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 金丝雀灰度路由实现
 * <p>
 * 同时实现 TrafficRouter（路由决策）和 CanaryConfigurable（配置管理）
 */
@Slf4j
public class CanaryRouter implements TrafficRouter, CanaryConfigurable {

    private final TrafficRouter delegate; // 原有的 LabelMatchRouter

    // 灰度配置：lingId -> percent (0-100)
    private final Map<String, CanaryConfig> canaryConfigs = new ConcurrentHashMap<>();

    public CanaryRouter(TrafficRouter delegate) {
        this.delegate = delegate;
    }

    @Override
    public LingInstance route(List<LingInstance> candidates, InvocationContext context) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }

        if (candidates.size() == 1) {
            return candidates.get(0);
        }

        String lingId = null;
        if (context != null) {
            if (context.getRuntime() != null) {
                lingId = context.getRuntime().getLingId();
            } else if (context.getTargetLingId() != null) {
                lingId = context.getTargetLingId();
            } else if (context.getServiceFQSID() != null) {
                String fqsid = context.getServiceFQSID();
                int idx = fqsid.indexOf(':');
                if (idx > 0) {
                    lingId = fqsid.substring(0, idx);
                }
            }
        }

        CanaryConfig config = lingId != null ? canaryConfigs.get(lingId) : null;

        // A. 有灰度配置，执行“角色正交化”分流逻辑
        if (config != null) {
            // 1. 明确金丝雀实例 (优先通过版本锁定)
            LingInstance targetCanary = findCanaryInstance(candidates, config.canaryVersion, null);

            // 2. 明确稳定版实例 (必须排除已锁定的金丝雀，防止“先灰后稳”导致的角色反转)
            LingInstance targetStable = findStableInstance(candidates, context, targetCanary);

            // 3. 执行权重分发
            boolean routeToCanary = config.percent > 0 && ThreadLocalRandom.current().nextInt(100) < config.percent;
            if (routeToCanary && targetCanary != null) {
                return targetCanary;
            }
            // 否则返回稳定版 (此时保证了稳定版绝不是金丝雀版本)
            return targetStable;
        }

        // B. 无灰度配置，但无 Labels 请求时，默认锁定稳定版 (防止回退到基础路由器的 50/50 随机分发)
        Map<String, String> labels = context != null ? context.getLabels() : null;
        if (labels == null || labels.isEmpty()) {
            return findStableInstance(candidates, context, null);
        }

        // C. 只有带特定 Labels 时才委托给基础路由器
        return delegate.route(candidates, context);
    }

    /**
     * 设置灰度配置
     */
    public void setCanaryConfig(String lingId, int percent, String canaryVersion) {
        if (percent < 0 || percent > 100) {
            throw new InvalidArgumentException("percent", "Canary percent must be 0-100");
        }

        // 禁止移除配置，即便为 0 也要保留，以防止降级回退导致的流量泄露
        canaryConfigs.put(lingId, new CanaryConfig(percent, canaryVersion));
        log.info("[{}] Canary config updated: {}% -> {}", lingId, percent, canaryVersion);
    }

    /**
     * 获取灰度配置
     */
    public CanaryConfig getCanaryConfig(String lingId) {
        return canaryConfigs.get(lingId);
    }

    /**
     * 移除灰度配置
     */
    public void removeCanaryConfig(String lingId) {
        if (lingId == null) {
            return;
        }
        canaryConfigs.remove(lingId);
    }

    /**
     * 获取灰度比例
     */
    @Override
    public int getCanaryPercent(String lingId) {
        CanaryConfig config = canaryConfigs.get(lingId);
        return config != null ? config.percent : 0;
    }

    private LingInstance findCanaryInstance(List<LingInstance> candidates, String canaryVersion,
            LingInstance stableInstance) {
        // 1. 优先匹配指定版本 (必须排除稳定版实例，防止自冲突)
        if (canaryVersion != null) {
            for (LingInstance inst : candidates) {
                if (inst != stableInstance && canaryVersion.equals(inst.getDefinition().getVersion())) {
                    return inst;
                }
            }
        }

        // 2. 否则返回第一个“非稳定版”实例
        for (LingInstance inst : candidates) {
            if (inst != stableInstance) {
                return inst;
            }
        }

        return null;
    }

    private LingInstance findStableInstance(List<LingInstance> candidates, InvocationContext context,
            LingInstance excludedInstance) {
        // 1. 优先从 Runtime 引用中获取池标记的 Default 实例 (如果是金丝雀版本标记了 Default，则不能用它)
        if (context != null && context.getRuntime() != null) {
            LingInstance defaultInst = context.getRuntime().getInstancePool().getDefault();
            if (defaultInst != null && defaultInst != excludedInstance && candidates.contains(defaultInst)) {
                return defaultInst;
            }
        }

        // 2. 降级逻辑：寻找第一个非排除对象的实例
        for (LingInstance inst : candidates) {
            if (inst != excludedInstance) {
                return inst;
            }
        }

        // 3. 实在没办法（如全是金丝雀版本，配置异常），则返回首位
        return candidates.get(0);
    }

    @Value
    public static class CanaryConfig {
        int percent;
        String canaryVersion;
    }
}
