package com.lingframe.api.security;

/**
 * 访问类型枚举
 * 定义了权限检查时的操作类型，如读、写。
 * 
 * @author LingFrame
 */
public enum AccessType {
    READ, 
    WRITE,
    EXECUTE // 考虑到未来可能有的执行权限，例如执行某个脚本或命令
}
