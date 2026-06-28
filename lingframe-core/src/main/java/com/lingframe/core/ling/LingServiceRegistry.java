package com.lingframe.core.ling;

import java.util.List;

/**
 * LingServiceRegistry 专注于服务契约目录。
 * 存储 FQSID → 方法签名/返回类型的映射，跨版本共享（接口契约不变）。
 * <p>
 * 接口服务的 FQSID 服务名部分本身就是接口全限定名，Pipeline 可直接用于 Class.forName。
 * 显式注解服务（@LingService）的 FQSID 服务名为用户自定义短 ID（如 query_user），
 * 不可直接用于类加载，需通过 {@link #getImplementationClassName} 查询真实实现类名。
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
     * 注册服务的实现类全限定名。
     * <p>
     * 对于接口服务，实现类名为实际实现该接口的 Bean 类名（冗余但无害）。
     * 对于显式注解服务，这是 Pipeline 将短 ID 映射到可加载类名的唯一途径。
     * <p>
     * 同一 FQSID 多版本注册时，实现类名应保持一致（灵珑契约约定）。
     *
     * @param serviceFQSID 服务全限定 ID
     * @param implClassName 实现类全限定名
     */
    void registerImplementationClassName(String serviceFQSID, String implClassName);

    /**
     * 获取服务的实现类全限定名。
     * <p>
     * Pipeline 解析阶段使用：当 FQSID 服务名不含 '.'（即为显式服务短 ID）时，
     * 通过此方法获取真实的可加载类名。
     *
     * @param serviceFQSID 服务全限定 ID
     * @return 实现类全限定名；未注册时返回 null
     */
    String getImplementationClassName(String serviceFQSID);

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
