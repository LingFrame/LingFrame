package com.lingframe.example.saas.inventory.controller;

import com.lingframe.api.annotation.LingReference;
import com.lingframe.api.context.LingCallContext;
import com.lingframe.example.mall.dto.ResponseResult;
import com.lingframe.example.saas.inventory.service.InventoryHoldService;
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
 * 带 TTL 的库存预占灵元 HTTP 入口。
 * <p>
 * 设计要点：
 * <ul>
 *   <li>灵元自暴露 @RestController，由 {@code WebInterfaceManager} 注册到灵核 Spring MVC，
 *       灵核 Spring MVC 只持有 {@code LingWebEntryHandler}（灵核类），不接触灵元类，保证灵元可热卸载。</li>
 *   <li>tenantId 从请求头 {@code X-Tenant-Id} 读取，写入 {@link LingCallContext} 的 label，
 *       请求结束由 {@code LingWebGovernanceFilter} 统一 clear，无 ThreadLocal 泄漏。</li>
 *   <li>{@code @LingReference InventoryHoldService} 灵元是唯一 provider（灵核无此契约），
 *       无双 provider 切流，灵元卸载后该能力消失。</li>
 * </ul>
 */
@Tag(name = "SaaS-Ling-3. 库存预占灵元入口 (灵元自暴露)", description = "灵核不存在的带 TTL 库存预占服务")
@RestController
@RequestMapping("/api/saas/ling/inventory")
public class InventoryHoldController {

    @LingReference
    private InventoryHoldService inventoryHoldService;

    @Operation(summary = "预占库存（带 TTL）", description = "预占后 ttlSeconds 内未确认扣减则自动释放")
    @PostMapping("/hold")
    public ResponseResult<String> holdStock(
            @RequestHeader(value = "X-Tenant-Id", required = false) String tenantId,
            @RequestParam Long skuId,
            @RequestParam Integer count,
            @RequestParam(defaultValue = "300") long ttlSeconds) {
        setTenantLabel(tenantId);
        String holdId = inventoryHoldService.holdStock(skuId, count, ttlSeconds);
        if (holdId == null) {
            return ResponseResult.fail("库存不足，预占失败");
        }
        return ResponseResult.success(holdId);
    }

    @Operation(summary = "确认扣减预占库存", description = "将预占转为真实扣减")
    @PostMapping("/confirm/{holdId}")
    public ResponseResult<String> confirmDeduct(
            @RequestHeader(value = "X-Tenant-Id", required = false) String tenantId,
            @PathVariable String holdId) {
        setTenantLabel(tenantId);
        boolean success = inventoryHoldService.confirmDeduct(holdId);
        return success ? ResponseResult.success("扣减成功") : ResponseResult.fail("单据不存在或已过期");
    }

    @Operation(summary = "释放预占库存", description = "订单取消等场景主动释放")
    @PostMapping("/release/{holdId}")
    public ResponseResult<String> releaseHold(
            @RequestHeader(value = "X-Tenant-Id", required = false) String tenantId,
            @PathVariable String holdId) {
        setTenantLabel(tenantId);
        boolean success = inventoryHoldService.releaseHold(holdId);
        return success ? ResponseResult.success("释放成功") : ResponseResult.fail("单据不存在或已过期");
    }

    @Operation(summary = "查询预占状态", description = "HOLDING / CONFIRMED / RELEASED / EXPIRED / NOT_FOUND")
    @GetMapping("/status/{holdId}")
    public ResponseResult<String> getHoldStatus(
            @RequestHeader(value = "X-Tenant-Id", required = false) String tenantId,
            @PathVariable String holdId) {
        setTenantLabel(tenantId);
        return ResponseResult.success(inventoryHoldService.getHoldStatus(holdId));
    }

    private void setTenantLabel(String tenantId) {
        if (tenantId != null) {
            Map<String, String> labels = new HashMap<>();
            labels.put("tenant", tenantId);
            LingCallContext.setLabels(labels);
        }
    }
}
