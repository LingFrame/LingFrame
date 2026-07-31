package com.lingframe.core.ling;

import com.lingframe.api.exception.RoutingArchitectureViolationException;
import com.lingframe.core.routing.ProviderDescriptor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class DefaultLingServiceRegistry implements LingServiceRegistry {
    // 接口契约目录：FQSID → 方法签名列表
    // 多版本共享：同一 FQSID 的接口契约跨版本不变，只存一份
    private final Map<String, List<String>> metadataCache = new ConcurrentHashMap<>();

    // 方法签名到返回类型的映射（`FQSID::methodSignature -> returnType`）
    private final Map<String, String> returnTypeCache = new ConcurrentHashMap<>();

    // 实现类名映射：FQSID → 实现类全限定名
    // 显式注解服务（短 ID）必须通过此映射才能在 Pipeline 中被正确加载为 Class。
    // 接口服务也会注册（冗余但幂等），不影响正确性。
    private final Map<String, String> implClassNameCache = new ConcurrentHashMap<>();

    // 反向索引：契约 ID → 灵元 ID 集合
    // 契约 ID 取 FQSID 去掉 "lingId:" 前缀后的剩余部分（裸契约名或短 ID）。
    // 路由层按接口类型查灵元时用此索引，避免 O(n) 遍历全表。
    private final Map<String, Set<String>> contractToLingIds = new ConcurrentHashMap<>();

    // 路由升维：契约 ID → 提供方描述符列表（含权重）
    // L0 provider 级路由的主索引，与 contractToLingIds 并行维护。
    // contractToLingIds 保留供旧调用方（dashboard 等）兼容使用，本索引供 ProviderWeightRouter 使用。
    private final Map<String, List<ProviderDescriptor>> providerIndex = new ConcurrentHashMap<>();

    @Override
    public void registerServiceMetadata(String serviceFQSID, String methodName,
            String[] parameterTypes, String returnType) {
        // 实现类名不在此存储：由 pipeline 从 FQSID 提取接口名 + 目标实例 ClassLoader 动态解析，
        // 避免多版本并存时 last-write-wins 导致路由错配。
        String signature = buildSignature(methodName, parameterTypes);
        metadataCache.compute(serviceFQSID, (key, existing) -> {
            List<String> methods = existing != null ? existing : new ArrayList<>();
            if (!methods.contains(signature)) {
                methods.add(signature);
            }
            return methods;
        });
        if (returnType != null) {
            returnTypeCache.put(serviceFQSID + "::" + signature, returnType);
        }
        // 维护反向索引：从 FQSID 提取 lingId 与 contractId，登记到反向索引
        indexContractToLing(serviceFQSID);
    }

    @Override
    public void registerImplementationClassName(String serviceFQSID, String implClassName) {
        if (serviceFQSID != null && implClassName != null) {
            implClassNameCache.put(serviceFQSID, implClassName);
        }
    }

    @Override
    public String getImplementationClassName(String serviceFQSID) {
        return serviceFQSID != null ? implClassNameCache.get(serviceFQSID) : null;
    }

    @Override
    public List<String> getProviderMethods(String serviceFQSID) {
        return metadataCache.getOrDefault(serviceFQSID, new ArrayList<>());
    }

    @Override
    public String getReturnType(String serviceFQSID, String methodSignature) {
        return returnTypeCache.get(serviceFQSID + "::" + methodSignature);
    }

    @Override
    public boolean hasMethod(String serviceFQSID, String methodName, String[] parameterTypes) {
        List<String> methods = metadataCache.get(serviceFQSID);
        if (methods == null)
            return false;
        return methods.contains(buildSignature(methodName, parameterTypes));
    }

    @Override
    public List<String> getServicesByLingId(String lingId) {
        String prefix = lingId + ":";
        List<String> services = new ArrayList<>();
        for (String fqsid : metadataCache.keySet()) {
            if (fqsid.startsWith(prefix)) {
                services.add(fqsid);
            }
        }
        return services;
    }

    @Override
    public List<String> getLingIdsByContractId(String contractId) {
        if (contractId == null || contractId.isEmpty()) {
            return new ArrayList<>();
        }
        // 路由升维：优先从 providerIndex 提取，保持与 getProvidersByContractId 一致
        List<ProviderDescriptor> providers = providerIndex.get(contractId);
        if (providers != null && !providers.isEmpty()) {
            List<String> lingIds = new ArrayList<>();
            for (ProviderDescriptor desc : providers) {
                if (!lingIds.contains(desc.getLingId())) {
                    lingIds.add(desc.getLingId());
                }
            }
            return lingIds;
        }
        // 兜底：FQSID 完整键命中时，提取 lingId 返回
        if (contractId.indexOf(':') > 0 && metadataCache.containsKey(contractId)) {
            String lingId = contractId.substring(0, contractId.indexOf(':'));
            List<String> result = new ArrayList<>();
            result.add(lingId);
            return result;
        }
        return new ArrayList<>();
    }

    @Override
    public List<ProviderDescriptor> getProvidersByContractId(String contractId) {
        if (contractId == null || contractId.isEmpty()) {
            return new ArrayList<>();
        }
        List<ProviderDescriptor> providers = providerIndex.get(contractId);
        return providers != null ? new ArrayList<>(providers) : new ArrayList<>();
    }

    @Override
    public Set<String> getAllContractIds() {
        // 返回不可变快照，避免外部修改影响内部索引
        return Collections.unmodifiableSet(new HashSet<>(providerIndex.keySet()));
    }

    @Override
    public Set<String> getContractsByLingId(String lingId) {
        if (lingId == null) {
            return Collections.emptySet();
        }
        Set<String> contracts = new HashSet<>();
        for (Map.Entry<String, List<ProviderDescriptor>> entry : providerIndex.entrySet()) {
            for (ProviderDescriptor desc : entry.getValue()) {
                if (lingId.equals(desc.getLingId())) {
                    contracts.add(entry.getKey());
                    break;
                }
            }
        }
        return Collections.unmodifiableSet(contracts);
    }

    @Override
    public void registerProvider(String contractId, String lingId, int weight) {
        registerProvider(contractId, lingId, null, weight);
    }

    @Override
    public void registerProvider(String contractId, String lingId, String version, int weight) {
        if (contractId == null || contractId.isEmpty() || lingId == null) {
            return;
        }
        ProviderDescriptor descriptor = new ProviderDescriptor(contractId, lingId, version, weight);
        String providerKey = descriptor.providerKey();
        // 幂等：同一 (contractId, providerKey) 已存在则更新 weight，不存在则追加
        providerIndex.compute(contractId, (key, existing) -> {
            List<ProviderDescriptor> list = existing != null ? existing : new ArrayList<>();
            // 查找是否已有同一 providerKey 的条目
            for (int i = 0; i < list.size(); i++) {
                if (list.get(i).providerKey().equals(providerKey)) {
                    // 已存在：weight 以最新为准
                    list.set(i, descriptor);
                    return list;
                }
            }
            // 新条目：支持多 Provider 注册
            list.add(descriptor);
            return list;
        });
    }

    @Override
    public void evictProvider(String lingId) {
        if (lingId == null) {
            return;
        }
        // 遍历所有契约，移除指定 lingId 的提供方条目（含所有版本）
        // 使用 compute 原子操作避免与并发 registerProvider 产生的 ConcurrentModificationException
        // （ArrayList.removeIf 在其他线程 compute 修改同一 list 时会抛 CME）
        for (String contractId : providerIndex.keySet()) {
            providerIndex.compute(contractId, (key, list) -> {
                if (list == null) {
                    return null;
                }
                list.removeIf(desc -> lingId.equals(desc.getLingId()));
                // 空列表返回 null 让 ConcurrentHashMap 回收 entry，防内存泄漏
                return list.isEmpty() ? null : list;
            });
        }
    }

    @Override
    public void unregisterProvider(String contractId, String providerKey) {
        if (contractId == null || providerKey == null) {
            return;
        }
        providerIndex.computeIfPresent(contractId, (key, list) -> {
            list.removeIf(desc -> providerKey.equals(desc.providerKey()));
            return list.isEmpty() ? null : list;
        });
    }

    @Override
    public void updateProviderWeight(String contractId, String lingId, int weight) {
        if (contractId == null || lingId == null) {
            return;
        }
        // 迭代期同一 lingId 可能注册为多个 providerKey（lingId:v1 + lingId:v2），
        // 全部匹配项均更新权重，不可仅改首个就 break——否则第二版本权重停滞，
        // 与 registerProvider/unregisterProvider 的 providerKey 键化语义背离
        providerIndex.computeIfPresent(contractId, (key, list) -> {
            for (int i = 0; i < list.size(); i++) {
                if (list.get(i).getLingId().equals(lingId)) {
                    list.set(i, list.get(i).withWeight(weight));
                }
            }
            return list;
        });
    }

    @Override
    public void evict(String lingId) {
        // 灵元整体卸载：清除该灵元所有接口契约和实现类名映射
        // 注：evict 与并发 registerServiceMetadata 可能导致反向索引短暂不一致，
        // 这属于 ConcurrentHashMap 弱一致性的可接受范围——卸载与注册并发极少，
        // 短暂残留由上层重试/熔断兜底。
        String prefix = lingId + ":";
        metadataCache.keySet().removeIf(k -> k.startsWith(prefix));
        returnTypeCache.keySet().removeIf(k -> k.startsWith(prefix));
        implClassNameCache.keySet().removeIf(k -> k.startsWith(prefix));
        // 清理反向索引：从每个契约的灵元集合中移除本灵元
        for (Set<String> lingIdSet : contractToLingIds.values()) {
            lingIdSet.remove(lingId);
        }
        // 清空空集合，防内存泄漏
        contractToLingIds.values().removeIf(Set::isEmpty);
        // 路由升维：同步清理 providerIndex
        evictProvider(lingId);
    }

    /**
     * 从 FQSID 提取 lingId 与 contractId，登记到反向索引。
     * contractId 为 FQSID 去掉 "lingId:" 前缀后的剩余（裸契约名或短 ID）。
     * <p>
     * 路由升维：同时维护 providerIndex（默认灵元 weight=0），
     * 使直接调 registerServiceMetadata 的调用方（dashboard 等）也能被 ProviderWeightRouter 查到。
     * 灵核侧通过 LingServiceRegistrar.forCore 注册，weight=100。
     */
    private void indexContractToLing(String serviceFQSID) {
        if (serviceFQSID == null) {
            return;
        }
        int idx = serviceFQSID.indexOf(':');
        if (idx <= 0 || idx >= serviceFQSID.length() - 1) {
            return; // 不含 ':' 或 ':' 在末尾，非合法 FQSID，忽略
        }
        String lingId = serviceFQSID.substring(0, idx);
        String contractId = serviceFQSID.substring(idx + 1);
        contractToLingIds.computeIfAbsent(contractId, k -> ConcurrentHashMap.newKeySet()).add(lingId);
        // 路由升维：同步登记 providerIndex，默认灵元 weight=0
        registerProvider(contractId, lingId, 0);
    }

    private String buildSignature(String methodName, String[] parameterTypes) {
        if (parameterTypes == null || parameterTypes.length == 0) {
            return methodName + "()";
        }
        return methodName + "(" + String.join(",", parameterTypes) + ")";
    }
}
