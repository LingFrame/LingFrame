package com.lingframe.dashboard.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 灵元实例代次的只读运行事实。
 * <p>
 * 实例 ID 用于区分同一灵元版本在重载期间并存的不同代次；接流资格和在途数量
 * 均来自运行时快照，不代表 Dashboard 可以直接修改实例状态。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LingInstanceSnapshotDTO {

    private String instanceId;
    private String lingId;
    private String version;
    private String status;
    private boolean defaultInstance;
    private boolean admissionDisabled;
    private boolean acceptingRequests;
    private long activeRequestCount;
    private boolean ready;
    private boolean draining;
}
