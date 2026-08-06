package com.lingframe.example.mall.controller.portal;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lingframe.example.mall.config.AuditLog;
import com.lingframe.example.mall.dto.ResponseResult;
import com.lingframe.example.mall.entity.SeckillActive;
import com.lingframe.example.mall.entity.Sku;
import com.lingframe.example.mall.entity.Spu;
import com.lingframe.example.mall.security.SecurityUtils;
import com.lingframe.example.mall.service.SeckillService;
import com.lingframe.example.mall.service.SkuService;
import com.lingframe.example.mall.service.SpuService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Tag(name = "2. 商城前台商品接口", description = "提供商品SPU/SKU列表、详情及秒杀专区抢购")
@RestController
@RequestMapping("/api/portal/products")
@RequiredArgsConstructor
public class ProductPortalController {

    private final SpuService spuService;
    private final SkuService skuService;
    private final SeckillService seckillService;

    @Operation(summary = "商品SPU列表", description = "查询所有处于上架状态的商品主SPU列表")
    @GetMapping("/list")
    public ResponseResult<List<Spu>> getSpuList() {
        List<Spu> list = spuService.list(new LambdaQueryWrapper<Spu>().eq(Spu::getStatus, 1));
        return ResponseResult.success(list);
    }

    @Operation(summary = "商品详情聚合", description = "根据SPU ID聚合查询SPU描述及其下挂载的所有SKU规格与定价列表")
    @GetMapping("/detail/{spuId}")
    public ResponseResult<Map<String, Object>> getDetail(@PathVariable Long spuId) {
        Spu spu = spuService.getById(spuId);
        if (spu == null || spu.getStatus() != 1) {
            throw new IllegalArgumentException("商品不存在或已被管理员下架");
        }
        
        List<Sku> skus = skuService.list(new LambdaQueryWrapper<Sku>()
                .eq(Sku::getSpuId, spuId)
                .eq(Sku::getStatus, 1));

        Map<String, Object> result = new HashMap<>();
        result.put("spu", spu);
        result.put("skus", skus);
        return ResponseResult.success(result);
    }

    @Operation(summary = "获取秒杀活动列表", description = "查询所有正在运行的秒杀活动")
    @GetMapping("/seckill/actives")
    public ResponseResult<List<SeckillActive>> getSeckillActives() {
        List<SeckillActive> list = seckillService.list();
        return ResponseResult.success(list);
    }

    @Operation(summary = "发起秒杀抢购", description = "异步削峰抢购，通过本地预扣除并返回排队凭证")
    @PostMapping("/seckill/order")
    @AuditLog(action = "SECKILL_ORDER", resource = "seckill")
    public ResponseResult<Map<String, String>> doSeckill(@RequestParam Long activeId) {
        Long userId = SecurityUtils.getUserId();
        String voucher = seckillService.seckill(userId, activeId);
        Map<String, String> result = new HashMap<>();
        result.put("voucher", voucher);
        result.put("status", "QUEUEING");
        return ResponseResult.success(result);
    }

    @Operation(summary = "轮询秒杀下单状态", description = "传入排队凭证轮询，返回排队中、下单成功订单ID或下单失败")
    @GetMapping("/seckill/status")
    public ResponseResult<Map<String, Object>> getSeckillStatus(@RequestParam String voucher) {
        Long userId = SecurityUtils.getUserId();
        Long resultId = seckillService.querySeckillStatus(userId, voucher);
        
        Map<String, Object> map = new HashMap<>();
        map.put("voucher", voucher);
        if (resultId == null) {
            map.put("status", "QUEUEING");
            map.put("orderId", null);
        } else if (resultId == -1L) {
            map.put("status", "FAIL");
            map.put("orderId", null);
        } else {
            map.put("status", "SUCCESS");
            map.put("orderId", resultId);
        }
        return ResponseResult.success(map);
    }
}
