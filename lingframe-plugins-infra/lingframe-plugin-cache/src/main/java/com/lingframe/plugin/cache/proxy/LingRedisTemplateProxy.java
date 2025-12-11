package com.lingframe.plugin.cache.proxy;

import com.lingframe.api.context.PluginContextHolder;
import com.lingframe.api.exception.PermissionDeniedException;
import com.lingframe.api.security.AccessType;
import com.lingframe.api.security.PermissionService;
import org.springframework.data.redis.core.RedisTemplate;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public class LingRedisTemplateProxy<K, V> {

    private final RedisTemplate<K, V> target;
    private final PermissionService permissionService;

    public LingRedisTemplateProxy(RedisTemplate<K, V> target, PermissionService permissionService) {
        this.target = target;
        this.permissionService = permissionService;
    }

    private void checkPermission(String operation) {
        String callerPluginId = PluginContextHolder.get();
        if (callerPluginId == null) return;

        boolean allowed = permissionService.isAllowed(callerPluginId, "cache:redis", AccessType.WRITE);
        permissionService.audit(callerPluginId, "cache:redis", operation, allowed);

        if (!allowed) {
            throw new PermissionDeniedException("Plugin [" + callerPluginId + "] denied access to Redis operation: " + operation);
        }
    }

    // Key相关的操作
    public Boolean hasKey(K key) {
        checkPermission("hasKey");
        return target.hasKey(key);
    }

    public void delete(K key) {
        checkPermission("delete");
        target.delete(key);
    }

    public void delete(Collection<K> keys) {
        checkPermission("deleteCollection");
        target.delete(keys);
    }

    public Boolean expire(K key, long timeout, TimeUnit unit) {
        checkPermission("expire");
        return target.expire(key, timeout, unit);
    }

    // String相关的操作
    public void set(K key, V value) {
        checkPermission("set");
        target.opsForValue().set(key, value);
    }

    public void set(K key, V value, long timeout, TimeUnit unit) {
        checkPermission("setWithTimeout");
        target.opsForValue().set(key, value, timeout, unit);
    }

    public V get(K key) {
        checkPermission("get");
        return target.opsForValue().get(key);
    }

    public V getAndDelete(K key) {
        checkPermission("getAndDelete");
        return target.opsForValue().getAndDelete(key);
    }

    public V getAndExpire(K key, long timeout, TimeUnit unit) {
        checkPermission("getAndExpire");
        return target.opsForValue().getAndExpire(key, timeout, unit);
    }

    public Long increment(K key, long delta) {
        checkPermission("increment");
        return target.opsForValue().increment(key, delta);
    }

    public Long decrement(K key, long delta) {
        checkPermission("decrement");
        return target.opsForValue().decrement(key, delta);
    }

    // Hash相关的操作
    public void hSet(K key, Object hashKey, V value) {
        checkPermission("hSet");
        target.opsForHash().put(key, hashKey, value);
    }

    public V hGet(K key, Object hashKey) {
        checkPermission("hGet");
        return (V) target.opsForHash().get(key, hashKey);
    }

    public Map<Object, V> hGetAll(K key) {
        checkPermission("hGetAll");
        return (Map<Object, V>) target.opsForHash().entries(key);
    }

    public void hDelete(K key, Object... hashKeys) {
        checkPermission("hDelete");
        target.opsForHash().delete(key, hashKeys);
    }

    // List相关的操作
    public Long lPush(K key, V... values) {
        checkPermission("lPush");
        return target.opsForList().leftPushAll(key, values);
    }

    public Long rPush(K key, V... values) {
        checkPermission("rPush");
        return target.opsForList().rightPushAll(key, values);
    }

    public V lPop(K key) {
        checkPermission("lPop");
        return target.opsForList().leftPop(key);
    }

    public V rPop(K key) {
        checkPermission("rPop");
        return target.opsForList().rightPop(key);
    }

    public List<V> lRange(K key, long start, long end) {
        checkPermission("lRange");
        return target.opsForList().range(key, start, end);
    }

    // Set相关的操作
    public Long sAdd(K key, V... values) {
        checkPermission("sAdd");
        return target.opsForSet().add(key, values);
    }

    public Set<V> sMembers(K key) {
        checkPermission("sMembers");
        return target.opsForSet().members(key);
    }

    public Boolean sIsMember(K key, Object o) {
        checkPermission("sIsMember");
        return target.opsForSet().isMember(key, o);
    }

    public Long sRemove(K key, Object... values) {
        checkPermission("sRemove");
        return target.opsForSet().remove(key, values);
    }

    // 获取原始的RedisTemplate（仅限内部使用）
    public RedisTemplate<K, V> getTarget() {
        return target;
    }
}