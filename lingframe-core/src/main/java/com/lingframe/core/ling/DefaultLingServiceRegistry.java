package com.lingframe.core.ling;

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

    // 反向索引：灵元 ID → FQSID 集合
    // 供 getServicesByLingId 直接 O(1) 命中，避免对 metadataCache 全表前缀扫描产生的 O(n) 遍历。
    private final Map<String, Set<String>> lingToServices = new ConcurrentHashMap<>();

    // 路由升维：契约 ID → 提供方描述符列表（含权重）
    // L0 provider 级路由的主索引，供 ProviderWeightRouter 使用。
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
        return metadataCache.getOrDefault(serviceFQSID, Collections.emptyList());
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
        if (lingId == null) {
            return Collections.emptyList();
        }
        Set<String> services = lingToServices.get(lingId);
        if (services == null || services.isEmpty()) {
            return Collections.emptyList();
        }
        return new ArrayList<>(services);
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
    public void registerProvider(String contractId, String lingId, String version, int weight) {
        if (contractId == null || contractId.isEmpty() || lingId == null) {
            return;
        }
        // 提供方注册：幂等写入 providerIndex（版本化候选），provider-only 注册路径同样被路由索引承接
        ProviderDescriptor descriptor = new ProviderDescriptor(contractId, lingId, version, weight);
        String providerKey = descriptor.providerKey();
        // 幂等：同一 (contractId, providerKey) 已存在则更新 weight，不存在则追加
        providerIndex.compute(contractId, (key, existing) -> {
            List<ProviderDescriptor> list = existing != null ? existing : new ArrayList<>();
            // 查找是否已有同一 providerKey 的条目
            boolean found = false;
            for (int i = 0; i < list.size(); i++) {
                if (list.get(i).providerKey().equals(providerKey)) {
                    // 已存在：weight 以最新为准
                    list.set(i, descriptor);
                    found = true;
                    break;
                }
            }
            if (!found) {
                // 新条目：支持多 Provider 注册
                list.add(descriptor);
            }
            // 无灵核基线时保证默认 provider 存在：契约权重全部为 0 → 首个 provider 提升为基线 100
            promoteDefaultBaseline(list);
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
                if (list.isEmpty()) {
                    // 空列表返回 null 让 ConcurrentHashMap 回收 entry，防内存泄漏
                    return null;
                }
                // 全量驱逐后仍保留「默认 provider 存在」不变量
                promoteDefaultBaseline(list);
                return list;
            });
        }
    }

    @Override
    public void evictProvider(String lingId, String version) {
        if (lingId == null || version == null) {
            return;
        }
        // 逐版本精确清理：仅移除该 lingId 指定版本的 provider descriptor，
        // 保留仍在服务的其他版本 provider（迭代期退役旧版本场景）
        for (String contractId : providerIndex.keySet()) {
            providerIndex.compute(contractId, (key, list) -> {
                if (list == null) {
                    return null;
                }
                list.removeIf(desc -> lingId.equals(desc.getLingId()) && version.equals(desc.getVersion()));
                if (list.isEmpty()) {
                    return null;
                }
                promoteDefaultBaseline(list);
                return list;
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
            if (list.isEmpty()) {
                return null;
            }
            promoteDefaultBaseline(list);
            return list;
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
            promoteDefaultBaseline(list);
            return list;
        });
    }

    /**
     * 维持「默认 provider」不变量：契约存在 provider 但权重全部为 0 时，
     * 提升首个 provider 权重到基线 100，避免无灵核场景下全部权重为 0 的空转展示。
     * <p>
     * Dashboard 的权重覆盖走 {@link com.lingframe.core.routing.ProviderWeightRouter}，
     * 与注册权重独立，不参与本方法判定。
     */
    private void promoteDefaultBaseline(List<ProviderDescriptor> list) {
        if (list == null || list.isEmpty()) {
            return;
        }
        for (ProviderDescriptor desc : list) {
            if (desc.getWeight() > 0) {
                return;
            }
        }
        list.set(0, list.get(0).withWeight(100));
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
        // 清理反向索引：灵元 → FQSID 整条移除
        lingToServices.remove(lingId);
        // 路由升维：同步清理 providerIndex
        evictProvider(lingId);
    }

    /**
     * 从 FQSID 提取 lingId 与 contractId，登记到反向索引。
     * contractId 为 FQSID 去掉 "lingId:" 前缀后的剩余（裸契约名或短 ID）。
     * <p>
     * 仅维护元数据层反向索引（灵元 → FQSID）：
     * <strong>不向 providerIndex 登记 version=null 的占位 provider</strong>。
     * provider 候选只由版本化注册路径产生（{@link LingServiceRegistrar} / {@code expose()}），
     * 占位 provider 会让同灵元多版本并存时出现「无版本幻影」，且无灵核基线提升会误命中占位符。
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
        // 反向索引：灵元 → FQSID，供 getServicesByLingId O(1) 命中
        lingToServices.computeIfAbsent(lingId, k -> ConcurrentHashMap.newKeySet()).add(serviceFQSID);
    }

    private String buildSignature(String methodName, String[] parameterTypes) {
        if (parameterTypes == null || parameterTypes.length == 0) {
            return methodName + "()";
        }
        return methodName + "(" + String.join(",", parameterTypes) + ")";
    }
}
