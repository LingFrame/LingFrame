package com.lingframe.core.ling;

import java.util.List;

/**
 * LingServiceRegistry 专注于方法级别的接口契约目录。
 * 存储 FQSID → 方法签名/返回类型的映射，跨版本共享（接口契约不变）。
 * 实现类名不在此存储：多版本下实现类名由 pipeline 从 FQSID 提取接口名
 * + 目标实例 ClassLoader 动态解析，避免 last-write-wins 导致路由错配。
 */
public interface LingServiceRegistry {

    /**
     * 注册方法级别元数据
     *
     * @param serviceFQSID   服务的全限定字符串短标识，如 "user:UserService"
     * @param methodName     方法名称
     * @param parameterTypes 方法参数类型签名
     * @param returnType     方法返回类型全限定名
     */
    void registerServiceMetadata(String serviceFQSID, String methodName, String[] parameterTypes, String returnType);


    /**
     * 提取指定服务的所有方法元数据。
     */
    List<String> getProviderMethods(String serviceFQSID);

    /**
     * 获取指定服务方法的返回类型。
     * 签名格式：methodName(paramType1,paramType2)
     */
    String getReturnType(String serviceFQSID, String methodSignature);

    /**
     * 验证某个服务接口上是否存在对应的方法参数签名。
     */
    boolean hasMethod(String serviceFQSID, String methodName, String[] parameterTypes);

    /**
     * 获取指定 lingId 下注册的所有服务 FQSID。
     */
    List<String> getServicesByLingId(String lingId);

    /**
     * 解除某个服务所有的方法绑定（在下线时调用）。
     */
    void evict(String lingId);
}
