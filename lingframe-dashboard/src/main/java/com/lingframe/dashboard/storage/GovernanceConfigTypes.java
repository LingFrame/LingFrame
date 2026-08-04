package com.lingframe.dashboard.storage;

/**
 * 治理配置持久化的 config_type 常量。
 * <p>
 * 落库时经 {@link GovernanceStorage} 的 ling_id + config_type 维度读写；
 * 本类集中管理类型串，禁止在控制器 / 服务层散落字面量。
 */
public final class GovernanceConfigTypes {

    /** 迁移阶段记录（新格式：phase/oldCandidate/newCandidate） */
    public static final String MIGRATION = "migration";

    /** 调用治理（InvocationPolicy，JSON） */
    public static final String INVOCATION = "invocation";

    /** 权限配置（GovernancePolicy，JSON） */
    public static final String PERMISSION = "permission";

    private GovernanceConfigTypes() {
    }
}
