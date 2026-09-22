package com.lingframe.dashboard.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 带操作结果的 Dashboard 变更结果。
 *
 * @param <T> 业务结果类型
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DashboardMutationResult<T> {

    private T data;
    private DashboardOperationOutcomeDTO outcome;
}
