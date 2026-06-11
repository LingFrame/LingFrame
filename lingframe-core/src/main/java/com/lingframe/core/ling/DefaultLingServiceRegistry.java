package com.lingframe.core.ling;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class DefaultLingServiceRegistry implements LingServiceRegistry {
    // 存储服务全限定名为 Key，方法元数据为 Value
    // `FQSID`（例如 `lingId:serviceName`）映射到方法签名列表
    // 例如 `methodName(paramType1,paramType2)`
    private final Map<String, List<String>> metadataCache = new ConcurrentHashMap<>();

    // 存储服务全限定名对应的实现类全限定名（`FQSID -> 类名`）
    private final Map<String, String> classCache = new ConcurrentHashMap<>();

    // 存储方法签名到返回类型的映射（`FQSID::methodSignature -> returnType`）
    private final Map<String, String> returnTypeCache = new ConcurrentHashMap<>();

    @Override
    public void registerServiceMetadata(String serviceFQSID, String className, String methodName,
            String[] parameterTypes, String returnType) {
        classCache.put(serviceFQSID, className);
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
    public String getServiceClassName(String serviceFQSID) {
        return classCache.get(serviceFQSID);
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
        for (String fqsid : classCache.keySet()) {
            if (fqsid.startsWith(prefix)) {
                services.add(fqsid);
            }
        }
        return services;
    }

    @Override
    public void evict(String lingId) {
        // 由于是 lingId，而 serviceFQSID 的格式通常是 "lingId:serviceName"
        String prefix = lingId + ":";
        metadataCache.keySet().removeIf(k -> k.startsWith(prefix));
        classCache.keySet().removeIf(k -> k.startsWith(prefix));
        returnTypeCache.keySet().removeIf(k -> k.startsWith(prefix));
    }

    private String buildSignature(String methodName, String[] parameterTypes) {
        if (parameterTypes == null || parameterTypes.length == 0) {
            return methodName + "()";
        }
        return methodName + "(" + String.join(",", parameterTypes) + ")";
    }
}
