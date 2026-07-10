package com.lingframe.core.ling;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
    private final Map<String, java.util.Set<String>> contractToLingIds = new ConcurrentHashMap<>();

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
        java.util.Set<String> lingIds = contractToLingIds.get(contractId);
        if (lingIds != null) {
            return new ArrayList<>(lingIds);
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
        for (java.util.Set<String> lingIdSet : contractToLingIds.values()) {
            lingIdSet.remove(lingId);
        }
        // 清空空集合，防内存泄漏
        contractToLingIds.values().removeIf(java.util.Set::isEmpty);
    }

    /**
     * 从 FQSID 提取 lingId 与 contractId，登记到反向索引。
     * contractId 为 FQSID 去掉 "lingId:" 前缀后的剩余（裸契约名或短 ID）。
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
    }

    private String buildSignature(String methodName, String[] parameterTypes) {
        if (parameterTypes == null || parameterTypes.length == 0) {
            return methodName + "()";
        }
        return methodName + "(" + String.join(",", parameterTypes) + ")";
    }
}
