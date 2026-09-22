package com.lingframe.dashboard.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 排空校验结果 DTO。
 * <p>
 * 命中前端 {@code confirmTransition} 调用 {@code /migration/drain-check} 端点判定 drainOk,
 * 替代硬编 {@code drainOk=true} 绕过排空校验的旧行为。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DrainCheckDTO {
    /** 是否已排空（退出方候选活跃请求数为 0） */
    private boolean drained;
    /** 退出方候选当前活跃请求数 */
    private long activeRequests;
}
