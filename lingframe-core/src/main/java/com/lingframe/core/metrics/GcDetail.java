package com.lingframe.core.metrics;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 单个 GC 收集器的统计详情。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GcDetail {
    /** GC 收集器名称，如 "ParNew"、"PS MarkSweep" */
    private String name;
    /** 收集次数 */
    private long count;
    /** 收集总耗时（毫秒） */
    private long timeMs;
}
