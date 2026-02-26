package com.lingframe.core.ling;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class DefaultLingRepository implements LingRepository {
    private final Map<String, LingRuntime> runtimes = new ConcurrentHashMap<>();

    @Override
    public void register(LingRuntime runtime) {
        if (runtime == null || runtime.getLingId() == null) {
            throw new IllegalArgumentException("Runtime or LingId cannot be null");
        }
        runtimes.put(runtime.getLingId(), runtime);
    }

    @Override
    public LingRuntime deregister(String lingId) {
        return runtimes.remove(lingId);
    }

    @Override
    public LingRuntime getRuntime(String lingId) {
        return runtimes.get(lingId);
    }

    @Override
    public boolean hasRuntime(String lingId) {
        return runtimes.containsKey(lingId);
    }

    @Override
    public Collection<LingRuntime> getAllRuntimes() {
        return runtimes.values();
    }
}
