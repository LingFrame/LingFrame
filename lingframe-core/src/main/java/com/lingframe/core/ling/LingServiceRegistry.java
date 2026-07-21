package com.lingframe.core.ling;

import java.util.List;
import java.util.Set;

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
     * 按契约 ID 反向索引灵元。
     * <p>
     * 契约 ID 可以是：
     * <ul>
     *   <li>完整 FQSID（如 {@code user-ling:com.example.UserService}）——精确等价匹配</li>
     *   <li>裸契约名（如 {@code com.example.UserService}）——匹配所有 lingId 下注册该契约的灵元</li>
     *   <li>短 ID（如 {@code sendSms}）——匹配所有 lingId 下注册该短 ID 的灵元</li>
     * </ul>
     * 路由层用此方法把「按接口类型」的查询从 O(n) 遍历降为 O(1) 反向索引命中。
     *
     * @param contractId 契约 ID（FQSID / 裸契约名 / 短 ID）
     * @return 注册了该契约的所有灵元 ID；未命中返回空列表
     */
    List<String> getLingIdsByContractId(String contractId);

    /**
     * 按契约 ID 查询所有提供方（含权重）。
     * <p>
     * 路由升维后的主入口，替代 {@link #getLingIdsByContractId}。
     * 返回的描述符列表包含每个提供方的 lingId、类型和权重，
     * 供 {@code ProviderWeightRouter} 做 L0 provider 级选路。
     *
     * @param contractId 契约 ID
     * @return 提供方描述符列表；未命中返回空列表
     */
    List<ProviderDescriptor> getProvidersByContractId(String contractId);

    /**
     * 列出所有已注册 provider 的契约 ID。
     * <p>
     * Dashboard 契约路由页面用此方法渲染「有多 provider 的契约」列表。
     *
     * @return 契约 ID 集合（不可变快照）；无任何注册时返回空集
     */
    Set<String> getAllContractIds();

    /**
     * 注册契约提供方。
     * <p>
     * 灵元和灵核在注册服务契约时同步调用，声明「本 lingId 以什么类型和权重提供该契约」。
     * 幂等：同一 (contractId, lingId) 重复注册只保留首次的 kind，weight 以最后一次为准。
     *
     * @param contractId 契约 ID
     * @param lingId 提供方灵元/灵核 ID
     * @param kind 提供方类型
     * @param weight 初始权重 0-100
     */
    void registerProvider(String contractId, String lingId, ProviderKind kind, int weight);

    /**
     * 驱逐指定 lingId 的所有提供方注册条目。
     * <p>
     * 灵元卸载时调用，移除该 lingId 在所有契约上的提供方登记。
     *
     * @param lingId 灵元/灵核 ID
     */
    void evictProvider(String lingId);

    /**
     * 更新提供方权重（Dashboard 下发）。
     * <p>
     * 运行期权重覆盖，用于 provider 级流量切分。
     * 不存在该 (contractId, lingId) 条目时静默忽略。
     *
     * @param contractId 契约 ID
     * @param lingId 灵元/灵核 ID
     * @param weight 新权重 0-100
     */
    void updateProviderWeight(String contractId, String lingId, int weight);

    /**
     * 解除某个服务所有的方法绑定（在下线时调用）。
     */
    void evict(String lingId);
}
