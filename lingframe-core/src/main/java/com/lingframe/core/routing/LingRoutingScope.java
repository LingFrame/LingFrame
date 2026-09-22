package com.lingframe.core.routing;

import java.util.Objects;

/** 精确限定灵元与契约的版本路由作用域，不解析服务别名。 */
public final class LingRoutingScope {
    private final String lingId;
    private final String contractId;

    /**
     * 创建由注册信息确定的作用域。
     * @param lingId 灵元标识，不得包含服务分隔符
     * @param contractId 注册的裸契约标识，不得包含服务分隔符
     * @throws IllegalArgumentException 标识为空或含服务分隔符
     */
    public LingRoutingScope(String lingId, String contractId) {
        this.lingId = check(lingId, "lingId");
        this.contractId = check(contractId, "contractId");
    }

    private static String check(String value, String name) {
        ProviderWeightSnapshot.requireKey(value, name);
        if (value.indexOf(':') >= 0) {
            throw new IllegalArgumentException(name + " must not contain ':'");
        }
        return value;
    }

    /** @return 灵元标识 */
    public String getLingId() {
        return lingId;
    }

    /** @return 裸契约标识 */
    public String getContractId() {
        return contractId;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof LingRoutingScope)) {
            return false;
        }
        LingRoutingScope scope = (LingRoutingScope) other;
        return lingId.equals(scope.lingId) && contractId.equals(scope.contractId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(lingId, contractId);
    }

    @Override
    public String toString() {
        return lingId + ":" + contractId;
    }
}
