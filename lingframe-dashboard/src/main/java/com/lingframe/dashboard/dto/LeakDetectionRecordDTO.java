package com.lingframe.dashboard.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 泄漏检测记录，缓存自 EventBus 接收的 LeakDetectionEvent。
 */
@Data
@Builder
public class LeakDetectionRecordDTO {
    private String lingId;
    private String version;
    /** true=ClassLoader 已回收, false=疑似泄漏 */
    private boolean collected;
    private String message;
    /** 检测模式：DEV_AGGRESSIVE / DEV_BOUNDED / PROD_PASSIVE */
    private String detectionMode;
    private long triggerTimeMillis;
    private long timestamp;
    /** 检测耗时 = timestamp - triggerTimeMillis */
    private long elapsedMillis;
}
