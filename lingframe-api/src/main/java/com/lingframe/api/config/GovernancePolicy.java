package com.lingframe.api.config;

import lombok.*;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 治理策略子节点
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GovernancePolicy implements Serializable {

    // 给字段赋默认值，防止 Builder 覆盖导致为 null (需要 @Builder.Default)
    @Builder.Default
    private List<PermissionRule> permissions = new ArrayList<>();

    @Builder.Default
    private List<AuditRule> audits = new ArrayList<>();

    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PermissionRule implements Serializable {
        private String methodPattern;
        private String permissionId;
    }

    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AuditRule implements Serializable {
        private String methodPattern;
        private String action;
        private boolean enabled = true;
    }

}
