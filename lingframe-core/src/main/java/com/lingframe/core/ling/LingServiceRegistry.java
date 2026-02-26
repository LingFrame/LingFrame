package com.lingframe.core.ling;

import java.util.List;

/**
 * LingServiceRegistry 专注于方法级别的服务路由表。
 * 其不再持有和依赖复杂的类加载逻辑，而是纯粹充当 {FQSID -> Method Metadata} 的关系型目录。
 * 未来取代老 ServiceRegistry (该类在旧版里混合了类加载和缓存，M3将只用作纯接口结构存储)。
 */
public interface LingServiceRegistry {

    /**
     * 注册方法级别元数据
     * 
     * @param serviceFQSID   服务的全限定字符串短标识，如 "user:UserService"
     * @param methodName     方法名称
     * @param parameterTypes 方法参数类型签名
     */
    void registerServiceMetadata(String serviceFQSID, String methodName, String[] parameterTypes);

    /**
     * 提取指定服务的所有方法元数据。
     */
    List<String> getProviderMethods(String serviceFQSID);

    /**
     * 验证某个服务接口上是否存在对应的方法参数签名。
     */
    boolean hasMethod(String serviceFQSID, String methodName, String[] parameterTypes);

    /**
     * 解除某个服务所有的方法绑定（在下线时调用）。
     */
    void evict(String pluginId);
}
