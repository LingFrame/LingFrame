package com.lingframe.example.mall.controller.portal;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lingframe.example.mall.dto.ResponseResult;
import com.lingframe.example.mall.entity.Coupon;
import com.lingframe.example.mall.entity.CouponUser;
import com.lingframe.example.mall.security.SecurityUtils;
import com.lingframe.example.mall.service.CouponService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "5. 前台优惠券接口", description = "提供用户领券、查询持有优惠券列表")
@RestController
@RequestMapping("/api/portal/coupons")
@RequiredArgsConstructor
public class CouponPortalController {

    private final CouponService couponService;

    @Operation(summary = "领取优惠券", description = "用户主动领取指定优惠券，扣减优惠券剩余数量并绑定账户")
    @PostMapping("/receive")
    public ResponseResult<Void> receive(@RequestParam Long couponId) {
        Long userId = SecurityUtils.getUserId();
        couponService.receiveCoupon(userId, couponId);
        return ResponseResult.success();
    }

    @Operation(summary = "获取我领取的优惠券列表", description = "根据状态(0-未使用, 1-已使用, 2-已过期)筛选当前用户的优惠券")
    @GetMapping("/my-list")
    public ResponseResult<List<CouponUser>> getMyCoupons(@RequestParam(required = false) Integer status) {
        Long userId = SecurityUtils.getUserId();
        List<CouponUser> list = couponService.getCouponsByUserId(userId, status);
        return ResponseResult.success(list);
    }

    @Operation(summary = "商城可领优惠券列表", description = "查询商城当前正在发放的所有优惠券基本信息")
    @GetMapping("/list-active")
    public ResponseResult<List<Coupon>> listActive() {
        List<Coupon> list = couponService.list(new LambdaQueryWrapper<Coupon>()
                .gt(Coupon::getStockCount, 0));
        return ResponseResult.success(list);
    }
}
