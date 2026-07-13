package com.lingframe.example.mall.controller.admin;

import com.lingframe.example.mall.config.AuditLog;
import com.lingframe.example.mall.dto.ResponseResult;
import com.lingframe.example.mall.entity.Sku;
import com.lingframe.example.mall.entity.Spu;
import com.lingframe.example.mall.security.SecurityUtils;
import com.lingframe.example.mall.service.InventoryService;
import com.lingframe.example.mall.service.SkuService;
import com.lingframe.example.mall.service.SpuService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@Tag(name = "7. 后台商品管理 (Admin)", description = "供商户管理员进行商品上架、规格管理与库存手动调增")
@RestController
@RequestMapping("/api/admin/products")
@RequiredArgsConstructor
public class ProductAdminController {

    private final SpuService spuService;
    private final SkuService skuService;
    private final InventoryService inventoryService;

    @Operation(summary = "商品SPU录入", description = "系统管理员新增商品SPU信息")
    @PostMapping("/spu/add")
    @PreAuthorize("hasAuthority('product:admin:add')")
    @AuditLog(action = "ADD_SPU", resource = "product")
    public ResponseResult<Spu> addSpu(@RequestBody Spu spu) {
        spuService.save(spu);
        return ResponseResult.success(spu);
    }

    @Operation(summary = "商品SKU规格录入", description = "系统管理员新增具体规格SKU信息")
    @PostMapping("/sku/add")
    @PreAuthorize("hasAuthority('product:admin:add')")
    @AuditLog(action = "ADD_SKU", resource = "product")
    public ResponseResult<Sku> addSku(@RequestBody Sku sku) {
        skuService.save(sku);
        return ResponseResult.success(sku);
    }

    @Operation(summary = "下架商品", description = "改变商品SPU状态为下架(0)")
    @PutMapping("/spu/disable/{spuId}")
    @PreAuthorize("hasAuthority('product:admin:disable')")
    @AuditLog(action = "DISABLE_SPU", resource = "product")
    public ResponseResult<Void> disableSpu(@PathVariable Long spuId) {
        Spu spu = spuService.getById(spuId);
        if (spu == null) {
            throw new IllegalArgumentException("商品不存在");
        }
        spu.setStatus(0); // 下架
        spuService.updateById(spu);
        return ResponseResult.success();
    }

    @Operation(summary = "手动调整SKU库存", description = "直接更改某SKU库存数，需强制录入流水日志")
    @PutMapping("/sku/adjust-stock")
    @PreAuthorize("hasAuthority('product:admin:adjustStock')")
    @AuditLog(action = "ADJUST_STOCK", resource = "inventory")
    public ResponseResult<Void> adjustStock(@RequestParam Long skuId, @RequestParam Integer stock) {
        String username = SecurityUtils.getLoginUser().getUsername();
        inventoryService.adjustStock(skuId, stock, username);
        return ResponseResult.success();
    }
}
