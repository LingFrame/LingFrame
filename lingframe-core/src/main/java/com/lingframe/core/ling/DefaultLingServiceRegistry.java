package com.lingframe.core.ling;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class DefaultLingServiceRegistry implements LingServiceRegistry {
    // 存储服务全限定名为 Key，方法元数据为 Value
    // FQSID (例如 "lingId:serviceName") -> List of Methods (e.g.,
    // "methodName(paramType1,paramType2)")
    private final Map<String, List<String>> metadataCache = new ConcurrentHashMap<>();

    @Override
    public void registerServiceMetadata(String serviceFQSID, String methodName, String[] parameterTypes) {
        metadataCache.computeIfAbsent(serviceFQSID, k -> new ArrayList<>())
                .add(buildSignature(methodName, parameterTypes));
    }

    @Override
    public List<String> getProviderMethods(String serviceFQSID) {
        return metadataCache.getOrDefault(serviceFQSID, new ArrayList<>());
    }

    @Override
    public boolean hasMethod(String serviceFQSID, String methodName, String[] parameterTypes) {
        List<String> methods = metadataCache.get(serviceFQSID);
        if (methods == null)
            return false;
        return methods.contains(buildSignature(methodName, parameterTypes));
    }

    @Override
    public void evict(String pluginId) {
        // 由于是 pluginId，而 serviceFQSID 的格式通常是 "pluginId:serviceName"
        String prefix = pluginId + ":";
        metadataCache.keySet().removeIf(k -> k.startsWith(prefix));
    }

    private String buildSignature(String methodName, String[] parameterTypes) {
        if (parameterTypes == null || parameterTypes.length == 0) {
            return methodName + "()";
        }
        return methodName + "(" + String.join(",", parameterTypes) + ")";
    }
}
