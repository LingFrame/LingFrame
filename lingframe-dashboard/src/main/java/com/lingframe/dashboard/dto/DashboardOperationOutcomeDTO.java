package com.lingframe.dashboard.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Dashboard 写操作的运行时与耐久化结果。
 * <p>
 * HTTP 成功只表示控制请求完成；调用方必须单独判断运行时、持久化、恢复和备份事实。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DashboardOperationOutcomeDTO {

    private boolean runtimeApplied;
    private boolean persisted;
    private boolean recoveryReady;
    private boolean backupReady;
    private String failureReason;
}
