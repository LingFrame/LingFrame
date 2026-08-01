package com.lingframe.example.saas.inventory.service;

/**
 * 带 TTL 的库存预占服务——灵核不存在的能力。
 * <p>
 * 灵核 {@code InventoryService.lockStock} 是"锁了就锁了"，无超时自动释放机制。
 * 灵元在此基础上提供带过期时间的预占：预占后若未在 TTL 内确认扣减，自动释放回可用库存。
 * <p>
 * 灵元自定义接口，不 extends MyBatis-Plus IService，无 delegate 桩代码。
 * 灵元是唯一 provider（灵核无此契约），无双 provider 切流，卸载后该能力消失。
 */
public interface InventoryHoldService {

    /**
     * 预占库存（带过期时间）。
     * <p>
     * 在灵核 lockStock 基础上叠加 TTL：预占后若未在 ttlSeconds 内确认扣减，
     * 自动释放回可用库存，避免下单超时导致的库存长期锁定。
     *
     * @param skuId       SKU ID
     * @param count       预占数量
     * @param ttlSeconds  预占有效期（秒），超时自动释放
     * @return 预占单据 ID，用于后续确认扣减或主动释放
     */
    String holdStock(Long skuId, Integer count, long ttlSeconds);

    /**
     * 确认扣减预占的库存（将预占转为真实扣减）。
     *
     * @param holdId 预占单据 ID
     * @return true=确认成功；false=单据不存在或已过期释放
     */
    boolean confirmDeduct(String holdId);

    /**
     * 主动释放预占的库存（订单取消等场景）。
     *
     * @param holdId 预占单据 ID
     * @return true=释放成功；false=单据不存在或已过期自动释放
     */
    boolean releaseHold(String holdId);

    /**
     * 查询预占单据状态。
     *
     * @param holdId 预占单据 ID
     * @return 状态描述：HOLDING / CONFIRMED / RELEASED / EXPIRED / NOT_FOUND
     */
    String getHoldStatus(String holdId);
}
