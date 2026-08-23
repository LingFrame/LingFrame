package com.lingframe.api.ling;

/**
 * 灵元统一健康与流量探测接口。
 * <p>
 * 灵元可按需注册此接口的 Spring Bean 以定制自身的健康检查与就绪响应；
 * 若灵元未提供自定义实现，灵珑容器将自动提供开箱即用的标准探针。
 */
public interface LingProbe {

    /**
     * 执行健康与流量连通性探测。
     *
     * @param contractId 关联探测的契约 ID（可选）
     * @return 探测响应状态（默认返回 "OK"）
     */
    default String probe(String contractId) {
        return "OK";
    }
}
