package com.lingframe.example.saas.seckill.controller;

import com.lingframe.api.annotation.LingReference;
import com.lingframe.api.context.LingCallContext;
import com.lingframe.example.mall.dto.ResponseResult;
import com.lingframe.example.mall.service.SeckillService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * SaaS 商城秒杀灵元 HTTP 入口。
 * <p>
 * 设计要点：
 * <ul>
 *   <li>灵元自暴露 @RestController，由 {@code WebInterfaceManager} 注册到灵核 Spring MVC，
 *       灵核 Spring MVC 只持有 {@code LingWebEntryHandler}（灵核类），不接触灵元类，保证灵元可热卸载。</li>
 *   <li>tenantId 从请求头 {@code X-Tenant-Id} 读取，写入 {@link LingCallContext} 的 label，
 *       灵元 SaaSSeckillServiceImpl 内部从 label 取 tenantId 做多租户治理。
 *       请求结束由 {@code LingWebGovernanceFilter} 统一 clear，无 ThreadLocal 泄漏。</li>
 *   <li>{@code @LingReference SeckillService} 不指定 lingId，走路由层双 provider 权重切流：
 *       默认灵核 100/灵元 0 走灵核原生 seckill；
 *       Dashboard 调权重到灵核 0/灵元 100 后，走灵元 SaaS 多租户 seckill（拓展点）。</li>
 * </ul>
 */
@Tag(name = "SaaS-Ling-2. 秒杀灵元入口 (灵元自暴露)", description = "灵元 HTTP 入口级切流演示：拓展灵核 SeckillService.seckill")
@RestController
@RequestMapping("/api/saas/ling/seckill")
public class SaaSSeckillController {

    // 不指定 lingId：走 SeckillService 契约的双 provider 权重切流
    @LingReference
    private SeckillService seckillService;

    @Operation(summary = "发起秒杀抢购", description = "灵元入口：走双 provider 切流调用 seckill")
    @PostMapping("/{activeId}")
    public ResponseResult<String> doSeckill(
            @RequestHeader(value = "X-Tenant-Id", required = false) String tenantId,
            @RequestParam Long userId,
            @PathVariable Long activeId) {
        setTenantLabel(tenantId);
        String voucher = seckillService.seckill(userId, activeId);
        return ResponseResult.success(voucher);
    }

    @Operation(summary = "轮询秒杀下单状态", description = "灵元入口：走双 provider 切流调用 querySeckillStatus")
    @GetMapping("/status/{voucher}")
    public ResponseResult<Long> getSeckillStatus(
            @RequestHeader(value = "X-Tenant-Id", required = false) String tenantId,
            @RequestParam Long userId,
            @PathVariable String voucher) {
        setTenantLabel(tenantId);
        Long orderId = seckillService.querySeckillStatus(userId, voucher);
        return ResponseResult.success(orderId);
    }

    private void setTenantLabel(String tenantId) {
        if (tenantId != null) {
            Map<String, String> labels = new HashMap<>();
            labels.put("tenant", tenantId);
            LingCallContext.setLabels(labels);
        }
    }
}
