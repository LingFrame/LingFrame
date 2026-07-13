package com.lingframe.example.mall.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.lingframe.example.mall.entity.SeckillActive;

public interface SeckillService extends IService<SeckillActive> {

    /**
     * 发起秒杀抢购 (预减缓存，放入削峰队列，返回排队凭证)
     */
    String seckill(Long userId, Long activeId);

    /**
     * 轮询秒杀下单结果
     * @return null-排队中, 订单ID-下单成功, -1-秒杀失败/已售罄
     */
    Long querySeckillStatus(Long userId, String voucher);
}
