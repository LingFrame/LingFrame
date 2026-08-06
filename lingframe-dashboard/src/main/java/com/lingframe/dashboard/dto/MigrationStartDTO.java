package com.lingframe.dashboard.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 迁移发起请求 DTO。
 * <p>
 * 同时用于发起迁移（CORE_EXCLUSIVE → MIGRATING）与发起迭代（LING_EXCLUSIVE → ITERATING），
 * 由端点路径区分。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MigrationStartDTO {
    /** 契约 ID */
    private String contractId;
    /** 退出方候选键（灵核 ID 或旧灵元 ID） */
    private String oldCandidate;
    /** 进入方候选键（灵元 ID 或灵元 ID:version） */
    private String newCandidate;
}
