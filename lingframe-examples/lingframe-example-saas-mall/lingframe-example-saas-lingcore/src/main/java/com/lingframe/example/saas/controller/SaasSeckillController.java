package com.lingframe.example.saas.controller;

import com.lingframe.api.annotation.LingReference;
import com.lingframe.example.mall.dto.ResponseResult;
import com.lingframe.example.saas.api.SeckillAbility;
import com.lingframe.example.saas.api.dto.SeckillResult;
import com.lingframe.example.saas.api.dto.SeckillStatusResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

@Tag(name = "SaaS-2. 促销秒杀专区代理 (SaaS 灵核)", description = "代理委派秒杀队列灵元执行削峰排队")
@RestController
@RequestMapping("/api/saas/portal/products")
public class SaasSeckillController {

    // 显性契约注入：使用 @LingReference 由灵珑底层自动织入动态路由治理代理，平滑跨越类加载器边界
    @LingReference
    private SeckillAbility seckillAbility;

    @Operation(summary = "发起秒杀抢购", description = "委派秒杀灵元执行本地预减并塞入削峰队列")
    @PostMapping("/seckill/order")
    public ResponseResult<SeckillResult> doSeckill(
            @RequestParam String tenantId,
            @RequestParam Long activeId) {
        SeckillResult result = seckillAbility.seckill(tenantId, 1L, activeId); // 演示固定用户ID
        return ResponseResult.success(result);
    }

    @Operation(summary = "轮询秒杀下单状态", description = "轮询凭证以获取真实的写库结果")
    @GetMapping("/seckill/status")
    public ResponseResult<SeckillStatusResult> getSeckillStatus(
            @RequestParam String tenantId,
            @RequestParam String voucher) {
        SeckillStatusResult result = seckillAbility.queryStatus(tenantId, 1L, voucher);
        return ResponseResult.success(result);
    }
}
