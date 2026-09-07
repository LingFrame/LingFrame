package com.lingframe.api.storage;

/**
 * 跨灵元事务穿透的回滚信号异常。
 * <p>
 * 对齐 Spring {@code UnexpectedRollbackException} 语义：下游灵元已声明回滚
 * （rollbackOnly 信号）时，非根 commit 或穿透过滤器回传检测到该信号，
 * 显式抛出本异常以触发上游根事务物理回滚——避免「下游已声明回滚、上游却静默提交」
 * 的半开事务。
 */
public class LingTransactionRollbackException extends RuntimeException {

    public LingTransactionRollbackException(String message) {
        super(message);
    }

    public LingTransactionRollbackException(String message, Throwable cause) {
        super(message, cause);
    }
}
