package com.lingframe.core.ling;

import com.lingframe.api.exception.InvalidArgumentException;
import java.lang.invoke.MethodHandle;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 纯方法级执行句柄缓存。
 * 替代原先庞大的 ServiceRegistry，不再持有 Proxy、Class 或相关监听逻辑。
 * 纯粹退化为 { FQSID -> MethodHandle } 的不可变映射存放所。
 */
public class InvokableMethodCache {
    private final Map<String, MethodHandle> cache = new ConcurrentHashMap<>();

    public void put(String fqsid, MethodHandle mh) {
        if (fqsid == null || mh == null) {
            throw new InvalidArgumentException("fqsid and mh", "FQSID and MethodHandle cannot be null");
        }
        cache.put(fqsid, mh);
    }

    public MethodHandle get(String fqsid) {
        return cache.get(fqsid);
    }

    public void remove(String fqsid) {
        cache.remove(fqsid);
    }

    public void clear() {
        cache.clear();
    }

    public int size() {
        return cache.size();
    }
}
