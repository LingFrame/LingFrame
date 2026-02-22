package com.lingframe.core.enums;

public enum LingStatus {
    UNLOADED, // 未加载
    LOADING, // 加载中
    LOADED, // 已加载 (未激活)
    STARTING, // 启动中
    ACTIVE, // 运行中
    STOPPING, // 停止中
    ERROR, // 异常
    UNINSTALLED // 已卸载
}
