package com.lingframe.example.mall.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.lingframe.example.mall.entity.Coupon;
import com.lingframe.example.mall.entity.CouponUser;

import java.util.List;

public interface CouponService extends IService<Coupon> {

    void receiveCoupon(Long userId, Long couponId);

    List<CouponUser> getCouponsByUserId(Long userId, Integer status);

    void useCoupon(Long couponUserId, Long userId, Long orderId);

    void releaseCoupon(Long couponUserId, Long userId);
}
