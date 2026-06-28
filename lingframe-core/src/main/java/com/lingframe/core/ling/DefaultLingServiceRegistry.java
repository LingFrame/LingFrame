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
    public void evict(String lingId) {
        // 灵元整体卸载：清除该灵元所有接口契约和实现类名映射
        String prefix = lingId + ":";
        metadataCache.keySet().removeIf(k -> k.startsWith(prefix));
        returnTypeCache.keySet().removeIf(k -> k.startsWith(prefix));
        implClassNameCache.keySet().removeIf(k -> k.startsWith(prefix));
    }

    private String buildSignature(String methodName, String[] parameterTypes) {
        if (parameterTypes == null || parameterTypes.length == 0) {
            return methodName + "()";
        }
        return methodName + "(" + String.join(",", parameterTypes) + ")";
    }
}
