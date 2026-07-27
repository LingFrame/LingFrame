package com.lingframe.dashboard.dto;

import com.lingframe.core.routing.MigrationPhase;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 迁移阶段查询结果 DTO。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MigrationPhaseDTO {
    /** 契约 ID */
    private String contractId;
    /** 当前迁移阶段名（{@link MigrationPhase#name()}） */
    private String phase;
    /** 退出方候选键；独占态时为 null */
    private String oldCandidate;
    /** 进入方候选键；独占态时为 null */
    private String newCandidate;
}
