package com.lingframe.core.ling;

import java.util.Collection;

/**
 * LingRepository 负责组件（LingRuntime）的存储和检索。
 */
public interface LingRepository {

    /**
     * 将一个新的 LingRuntime 注册到仓储中。
     *
     * @param runtime 要注册的Ling运行时聚合根
     */
    void register(LingRuntime runtime);

    /**
     * 移除指定的 LingRuntime。
     *
     * @param lingId 组件的唯一标识
     * @return 如果找到并成功移除则返回该聚合根，否则为 null
     */
    LingRuntime deregister(String lingId);

    /**
     * 根据组件 ID 获取对应的 LingRuntime。
     *
     * @param lingId 组件 ID
     * @return 对应的 LingRuntime
     */
    LingRuntime getRuntime(String lingId);

    /**
     * 检查组件是否存在
     */
    boolean hasRuntime(String lingId);

    /**
     * 获取存储的所有被激活的组件。
     */
    Collection<LingRuntime> getAllRuntimes();
}
