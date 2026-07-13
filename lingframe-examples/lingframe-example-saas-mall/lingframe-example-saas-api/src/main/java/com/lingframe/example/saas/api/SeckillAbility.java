package com.lingframe.example.saas.api;

import com.lingframe.example.saas.api.dto.SeckillResult;
import com.lingframe.example.saas.api.dto.SeckillStatusResult;

public interface SeckillAbility {

    SeckillResult seckill(String tenantId, Long userId, Long activeId);

    SeckillStatusResult queryStatus(String tenantId, Long userId, String voucher);
}
