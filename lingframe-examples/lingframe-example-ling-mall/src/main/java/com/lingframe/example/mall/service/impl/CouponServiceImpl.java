package com.lingframe.example.mall.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.lingframe.example.mall.entity.Coupon;
import com.lingframe.example.mall.entity.CouponUser;
import com.lingframe.example.mall.mapper.CouponMapper;
import com.lingframe.example.mall.mapper.CouponUserMapper;
import com.lingframe.example.mall.service.CouponService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.List;

@Service
@RequiredArgsConstructor
public class CouponServiceImpl extends ServiceImpl<CouponMapper, Coupon> implements CouponService {

    private final CouponMapper couponMapper;
    private final CouponUserMapper couponUserMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void receiveCoupon(Long userId, Long couponId) {
        Coupon coupon = couponMapper.selectById(couponId);
        if (coupon == null) {
            throw new IllegalArgumentException("优惠券不存在");
        }
        Date now = new Date();
        if (now.before(coupon.getStartTime()) || now.after(coupon.getEndTime())) {
            throw new IllegalArgumentException("优惠券不在领取有效期内");
        }

        // 校验是否已领取过 (限制每人限领一张以满足生产场景约束)
        Long count = couponUserMapper.selectCount(new LambdaQueryWrapper<CouponUser>()
                .eq(CouponUser::getCouponId, couponId)
                .eq(CouponUser::getUserId, userId));
        if (count > 0) {
            throw new IllegalArgumentException("您已领过该优惠券，每人限领一张");
        }

        // 扣减库存数 (DB级扣减，防止超发)
        boolean success = update(null, Wrappers.<Coupon>lambdaUpdate()
                .eq(Coupon::getId, couponId)
                .gt(Coupon::getStockCount, 0)
                .setSql("stock_count = stock_count - 1"));
        
        if (!success) {
            throw new IllegalArgumentException("优惠券已被领光");
        }

        // 写入用户优惠券关联
        CouponUser couponUser = new CouponUser();
        couponUser.setCouponId(couponId);
        couponUser.setUserId(userId);
        couponUser.setStatus(0); // 未使用
        couponUser.setReceiveTime(new Date());
        couponUserMapper.insert(couponUser);
    }

    @Override
    public List<CouponUser> getCouponsByUserId(Long userId, Integer status) {
        return couponUserMapper.selectList(new LambdaQueryWrapper<CouponUser>()
                .eq(CouponUser::getUserId, userId)
                .eq(status != null, CouponUser::getStatus, status)
                .orderByDesc(CouponUser::getReceiveTime));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void useCoupon(Long couponUserId, Long userId, Long orderId) {
        CouponUser couponUser = couponUserMapper.selectById(couponUserId);
        if (couponUser == null || !couponUser.getUserId().equals(userId)) {
            throw new IllegalArgumentException("优惠券无效");
        }
        if (couponUser.getStatus() != 0) {
            throw new IllegalArgumentException("优惠券已被使用或已失效");
        }

        couponUser.setStatus(1); // 已使用
        couponUser.setOrderId(orderId);
        couponUser.setUseTime(new Date());
        couponUserMapper.updateById(couponUser);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void releaseCoupon(Long couponUserId, Long userId) {
        CouponUser couponUser = couponUserMapper.selectById(couponUserId);
        if (couponUser != null && couponUser.getUserId().equals(userId)) {
            couponUser.setStatus(0); // 恢复未使用
            couponUser.setOrderId(null);
            couponUser.setUseTime(null);
            couponUserMapper.updateById(couponUser);
        }
    }
}
