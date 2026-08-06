package com.lingframe.example.mall.service.impl;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.crypto.SecureUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.lingframe.example.mall.config.AuditLog;
import com.lingframe.example.mall.dto.OrderCreateRequest;
import com.lingframe.example.mall.dto.OrderItemRequest;
import com.lingframe.example.mall.entity.*;
import com.lingframe.example.mall.mapper.*;
import com.lingframe.example.mall.service.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
@RequiredArgsConstructor
public class OrderServiceImpl extends ServiceImpl<OrderMapper, Order> implements OrderService {

    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final LogisticsMapper logisticsMapper;
    private final OrderRefundMapper orderRefundMapper;
    
    private final SkuService skuService;
    private final SpuService spuService;
    private final InventoryService inventoryService;
    private final CartService cartService;
    private final MemberMapper memberMapper;
    private final MemberLevelMapper memberLevelMapper;
    private final MemberService memberService;
    private final CouponService couponService;
    private final CouponUserMapper couponUserMapper;
    private final CouponMapper couponMapper;
    private final NotificationMapper notificationMapper;

    private static final ScheduledExecutorService EXECUTOR = Executors.newScheduledThreadPool(2);
    
    // 幂等性校验集：保存已成功处理支付回调的订单 Sn
    private static final Set<String> PROCESSED_PAYMENTS = ConcurrentHashMap.newKeySet();

    @Override
    @Transactional(rollbackFor = Exception.class)
    @AuditLog(action = "CREATE_ORDER", resource = "order")
    public Order createOrder(Long userId, OrderCreateRequest request) {
        String orderSn = DateUtil.format(new Date(), "yyyyMMddHHmmss") + RandomUtil.randomNumbers(6);
        
        // 1. 获取会员折扣率
        BigDecimal discountRate = BigDecimal.ONE;
        Member member = memberMapper.selectById(userId);
        if (member != null) {
            MemberLevel level = memberLevelMapper.selectOne(new LambdaQueryWrapper<MemberLevel>()
                    .eq(MemberLevel::getVipLevel, member.getVipLevel()));
            if (level != null) {
                discountRate = level.getDiscountRate();
            }
        }

        BigDecimal originalTotalAmount = BigDecimal.ZERO;
        List<OrderItem> itemsToSave = new ArrayList<>();

        // 2. 校验 SKU 与并发锁库存
        for (OrderItemRequest itemReq : request.getItems()) {
            Sku sku = skuService.getById(itemReq.getProductId()); // 订单传入的 productId 即 skuId
            if (sku == null || sku.getStatus() != 1) {
                throw new IllegalArgumentException("商品型号不存在或已下架");
            }
            Spu spu = spuService.getById(sku.getSpuId());
            if (spu == null || spu.getStatus() != 1) {
                throw new IllegalArgumentException("商品已下架");
            }

            // 并发扣减（锁定可用库存，若不够则抛出异常导致事务回滚）
            boolean locked = inventoryService.lockStock(sku.getId(), itemReq.getQuantity());
            if (!locked) {
                throw new IllegalArgumentException("商品型号库存不足，下单失败");
            }

            BigDecimal price = sku.getPrice();
            BigDecimal itemTotal = price.multiply(new BigDecimal(itemReq.getQuantity()));
            originalTotalAmount = originalTotalAmount.add(itemTotal);

            // 组合规格名
            Map<String, String> specs = JSONUtil.toBean(sku.getSpecsJson(), Map.class);
            String specsStr = String.join(", ", specs.values());

            OrderItem orderItem = new OrderItem();
            orderItem.setSpuId(spu.getId());
            orderItem.setSkuId(sku.getId());
            orderItem.setProductName(spu.getName() + " (" + specsStr + ")");
            orderItem.setQuantity(itemReq.getQuantity());
            orderItem.setPrice(price);
            itemsToSave.add(orderItem);
        }

        // 3. 计算应付金额 (会员打折)
        BigDecimal totalAmount = originalTotalAmount.multiply(discountRate);

        // 4. 优惠券抵扣
        if (request.getCouponUserId() != null) {
            CouponUser couponUser = couponUserMapper.selectById(request.getCouponUserId());
            if (couponUser == null || !couponUser.getUserId().equals(userId) || couponUser.getStatus() != 0) {
                throw new IllegalArgumentException("所选优惠券无效或已被使用");
            }
            Coupon coupon = couponMapper.selectById(couponUser.getCouponId());
            if (coupon == null) {
                throw new IllegalArgumentException("优惠券数据异常");
            }

            // 校验使用门槛
            if (totalAmount.compareTo(coupon.getMinPoint()) < 0) {
                throw new IllegalArgumentException("订单金额未达到优惠券使用门槛");
            }

            // 扣减优惠券面额
            totalAmount = totalAmount.subtract(coupon.getAmount());
            if (totalAmount.compareTo(BigDecimal.ZERO) < 0) {
                totalAmount = BigDecimal.ZERO; // 应付最低为0
            }

            // 锁定使用优惠券
            couponService.useCoupon(request.getCouponUserId(), userId, null); // 暂不填 orderId，后续绑定
        }

        // 5. 写入订单主表
        Order order = new Order();
        order.setOrderSn(orderSn);
        order.setUserId(userId);
        order.setTotalAmount(totalAmount);
        order.setStatus(0); // 待付款
        order.setReceiverName(request.getReceiverName());
        order.setReceiverPhone(request.getReceiverPhone());
        order.setReceiverAddress(request.getReceiverAddress());
        order.setCreatedAt(new Date());
        orderMapper.insert(order);

        // 6. 写入订单明细并绑定优惠券
        if (request.getCouponUserId() != null) {
            CouponUser cu = couponUserMapper.selectById(request.getCouponUserId());
            cu.setOrderId(order.getId());
            couponUserMapper.updateById(cu);
        }

        for (OrderItem item : itemsToSave) {
            item.setOrderId(order.getId());
            orderItemMapper.insert(item);
            
            // 清理购物车
            cartService.deleteCart(userId, item.getSkuId());
        }

        // 7. 注册 15 分钟超时取消任务
        EXECUTOR.schedule(() -> {
            try {
                autoCancelOrderIfUnpaid(order.getId());
            } catch (Exception e) {
                log.error("Auto cancel order error for id: " + order.getId(), e);
            }
        }, 15, TimeUnit.MINUTES);

        return order;
    }

    private void autoCancelOrderIfUnpaid(Long orderId) {
        Order order = orderMapper.selectById(orderId);
        if (order != null && order.getStatus() == 0) {
            log.info("Order Sn {} unpaid for 15 minutes, system auto canceling...", order.getOrderSn());
            cancelOrder(orderId, order.getUserId());
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    @AuditLog(action = "CANCEL_ORDER", resource = "order")
    public void cancelOrder(Long orderId, Long userId) {
        Order order = orderMapper.selectById(orderId);
        if (order == null) {
            throw new IllegalArgumentException("订单不存在");
        }
        if (!order.getUserId().equals(userId)) {
            throw new IllegalArgumentException("无权操作此订单");
        }
        if (order.getStatus() != 0) {
            throw new IllegalArgumentException("当前订单状态不支持取消");
        }

        order.setStatus(4); // 已取消
        order.setCanceledAt(new Date());
        orderMapper.updateById(order);

        // 1. 释放锁定的 SKU 库存
        List<OrderItem> items = orderItemMapper.selectList(new LambdaQueryWrapper<OrderItem>()
                .eq(OrderItem::getOrderId, orderId));
        for (OrderItem item : items) {
            inventoryService.releaseStock(item.getSkuId(), item.getQuantity());
        }

        // 2. 释放退回优惠券
        CouponUser couponUser = couponUserMapper.selectOne(new LambdaQueryWrapper<CouponUser>()
                .eq(CouponUser::getOrderId, orderId));
        if (couponUser != null) {
            couponService.releaseCoupon(couponUser.getId(), userId);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void payOrder(String orderSn) {
        // 提供基础修改，直接生成签名调用 webhook
        String mockPaySn = "PAY_" + IdUtil.simpleUUID().substring(0, 12);
        String rawSignSource = orderSn + "&" + mockPaySn + "&lingmall-webhook-key";
        String sign = SecureUtil.md5(rawSignSource);
        
        // 走标准的安全支付回调，以确保业务完整性
        handlePayCallback(orderSn, mockPaySn, sign);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    @AuditLog(action = "PAY_CALLBACK_WEBHOOK", resource = "order")
    public void handlePayCallback(String orderSn, String paySn, String sign) {
        // 1. 安全验签
        String expectedSign = SecureUtil.md5(orderSn + "&" + paySn + "&lingmall-webhook-key");
        if (!expectedSign.equalsIgnoreCase(sign)) {
            log.error("Webhook signature verification failed! orderSn: {}, paySn: {}", orderSn, paySn);
            throw new IllegalArgumentException("支付回调验签失败，拒绝处理");
        }

        // 2. 幂等性校验，防止重复回调处理
        if (PROCESSED_PAYMENTS.contains(orderSn)) {
            log.warn("Payment callback for orderSn: {} has already been processed (Idempotent Bypassed).", orderSn);
            return;
        }

        Order order = orderMapper.selectOne(new LambdaQueryWrapper<Order>().eq(Order::getOrderSn, orderSn));
        if (order == null) {
            throw new IllegalArgumentException("订单流水号不存在");
        }
        if (order.getStatus() != 0) {
            log.info("Order Sn {} status is not pending payment, bypassed. status: {}", orderSn, order.getStatus());
            return;
        }

        // 3. 更新订单状态为待发货
        order.setStatus(1); // 待发货
        order.setPaidAt(new Date());
        orderMapper.updateById(order);

        // 4. 累加会员积分与成长值 (1元 = 1 积分 = 1 成长值)
        int score = order.getTotalAmount().intValue();
        if (score > 0) {
            memberService.addPointsAndGrowth(order.getUserId(), score, score);
        }

        // 5. 写入站内信私信通知
        Notification notice = new Notification();
        notice.setTitle("订单付款成功通知");
        notice.setContent("您尾号为 " + order.getOrderSn().substring(order.getOrderSn().length() - 6) + 
                " 的订单已成功支付人民币 " + order.getTotalAmount() + " 元，系统已通知仓储发货！" +
                "本次消费共为您累加 " + score + " 积分与会员成长值。");
        notice.setType(1); // 个人通知
        notice.setReceiverId(order.getUserId());
        notice.setCreatedAt(new Date());
        notificationMapper.insert(notice);

        // 标记幂等集已成功处理
        PROCESSED_PAYMENTS.add(orderSn);
        log.info("Payment callback processed successfully for order Sn: {}, Pay Sn: {}", orderSn, paySn);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    @AuditLog(action = "SHIP_ORDER", resource = "order")
    public void shipOrder(Long orderId, String deliveryCompany, String deliverySn) {
        Order order = orderMapper.selectById(orderId);
        if (order == null) {
            throw new IllegalArgumentException("订单不存在");
        }
        if (order.getStatus() != 1) {
            throw new IllegalArgumentException("订单非待发货状态，无法发货");
        }

        // 1. 改变订单状态为待收货
        order.setStatus(2); // 待收货
        orderMapper.updateById(order);

        // 2. 真实扣除锁定的 SKU 库存
        List<OrderItem> items = orderItemMapper.selectList(new LambdaQueryWrapper<OrderItem>()
                .eq(OrderItem::getOrderId, orderId));
        for (OrderItem item : items) {
            inventoryService.deductLockedStock(item.getSkuId(), item.getQuantity());
        }

        // 3. 新建物流单与轨迹
        Logistics logistics = new Logistics();
        logistics.setOrderId(orderId);
        logistics.setDeliveryCompany(deliveryCompany);
        logistics.setDeliverySn(deliverySn);
        logistics.setStatus(1); // 运输中

        JSONArray trace = new JSONArray();
        Map<String, String> step1 = new HashMap<>();
        step1.put("time", DateUtil.now());
        step1.put("content", "【已发货】包裹已出库，由顺丰速运承运，单号: " + deliverySn);
        trace.add(step1);
        logistics.setTraceData(JSONUtil.toJsonStr(trace));
        logisticsMapper.insert(logistics);

        // 4. 发送个人私信通知发货
        Notification notice = new Notification();
        notice.setTitle("订单已发货通知");
        notice.setContent("您购买的商品已通过【" + deliveryCompany + "】寄出，运单号为 " + deliverySn + "，请耐心等待配送并保持电话畅通。");
        notice.setType(1);
        notice.setReceiverId(order.getUserId());
        notice.setCreatedAt(new Date());
        notificationMapper.insert(notice);

        // 5. 模拟物流动态追踪更新
        simulateLogistics(orderId);
    }

    private void simulateLogistics(Long orderId) {
        EXECUTOR.schedule(() -> appendTrace(orderId, 2, "【快递已揽收】快件准备发出，预计后天送达。"), 5, TimeUnit.SECONDS);
        EXECUTOR.schedule(() -> appendTrace(orderId, 2, "【转运中心】快件在顺丰北京转运中心装车，正准备发往目的地。"), 10, TimeUnit.SECONDS);
        EXECUTOR.schedule(() -> appendTrace(orderId, 2, "【派送中】快件在目的地营业点完成分拣，正由派件员王师傅(13911112222)派送。"), 15, TimeUnit.SECONDS);
        EXECUTOR.schedule(() -> {
            appendTrace(orderId, 3, "【已签收】快件已由前台收发室代收签收，感谢您的支持！");
            
            // 自动改变订单状态为已完成
            Order order = orderMapper.selectById(orderId);
            if (order != null && order.getStatus() == 2) {
                order.setStatus(3); // 已完成
                orderMapper.updateById(order);
                
                // 再次通知用户订单已妥投
                Notification notice = new Notification();
                notice.setTitle("订单已送达签收");
                notice.setContent("您的包裹已签收完结，如果任何问题，可以在订单明细页点击【申请退款】发起售后请求。");
                notice.setType(1);
                notice.setReceiverId(order.getUserId());
                notice.setCreatedAt(new Date());
                notificationMapper.insert(notice);
            }
        }, 20, TimeUnit.SECONDS);
    }

    private void appendTrace(Long orderId, Integer status, String content) {
        try {
            Logistics logistics = logisticsMapper.selectOne(new LambdaQueryWrapper<Logistics>().eq(Logistics::getOrderId, orderId));
            if (logistics != null) {
                logistics.setStatus(status);
                JSONArray trace = JSONUtil.parseArray(logistics.getTraceData());
                Map<String, String> step = new HashMap<>();
                step.put("time", DateUtil.now());
                step.put("content", content);
                trace.add(step);
                logistics.setTraceData(JSONUtil.toJsonStr(trace));
                logisticsMapper.updateById(logistics);
            }
        } catch (Exception e) {
            log.error("Simulated trace append error for order: " + orderId, e);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    @AuditLog(action = "APPLY_REFUND", resource = "order")
    public void applyRefund(Long orderId, Long userId, String reason, BigDecimal amount) {
        Order order = orderMapper.selectById(orderId);
        if (order == null || !order.getUserId().equals(userId)) {
            throw new IllegalArgumentException("订单不存在");
        }
        // 只有 1-待发货, 2-待收货, 3-已完成 的订单可以发起退款
        if (order.getStatus() != 1 && order.getStatus() != 2 && order.getStatus() != 3) {
            throw new IllegalArgumentException("当前订单状态不支持申请售后退款");
        }
        if (amount.compareTo(order.getTotalAmount()) > 0 || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("退款金额必须大于0且不能超过实付总价: " + order.getTotalAmount());
        }

        // 创建退款售后单
        OrderRefund refund = new OrderRefund();
        refund.setOrderId(orderId);
        refund.setUserId(userId);
        refund.setAmount(amount);
        refund.setReason(reason);
        refund.setStatus(0); // 申请中
        refund.setCreatedAt(new Date());
        orderRefundMapper.insert(refund);

        // 修改订单状态为 5 (退款中)
        order.setStatus(5);
        orderMapper.updateById(order);

        // 写入站内通知
        Notification notice = new Notification();
        notice.setTitle("售后退款申请已提交");
        notice.setContent("您的售后退款申请已提交成功，退款金额: " + amount + " 元。商家将在48小时内审核。");
        notice.setType(1);
        notice.setReceiverId(userId);
        notice.setCreatedAt(new Date());
        notificationMapper.insert(notice);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    @AuditLog(action = "AUDIT_REFUND", resource = "order")
    public void auditRefund(Long refundId, Integer status, String rejectReason, String operator) {
        OrderRefund refund = orderRefundMapper.selectById(refundId);
        if (refund == null || refund.getStatus() != 0) {
            throw new IllegalArgumentException("售后退款单不存在或已被审核过");
        }

        Order order = orderMapper.selectById(refund.getOrderId());
        if (order == null) {
            throw new IllegalArgumentException("订单数据异常");
        }

        if (status == 1) {
            // 同意退款
            refund.setStatus(1); // 已退回
            refund.setAuditTime(new Date());
            orderRefundMapper.updateById(refund);

            order.setStatus(6); // 已退款
            orderMapper.updateById(order);

            // 1. 退货退款成功，释放/回退 SKU 库存 (可用库存重新加回去)
            List<OrderItem> items = orderItemMapper.selectList(new LambdaQueryWrapper<OrderItem>()
                    .eq(OrderItem::getOrderId, order.getId()));
            for (OrderItem item : items) {
                // 原先发货扣减了可用库存，现在退货退款，可用库存加回来（即 adjustStock 增加）
                Inventory inventory = inventoryService.getById(item.getSkuId());
                if (inventory != null) {
                    inventoryService.adjustStock(item.getSkuId(), inventory.getStock() + item.getQuantity(), "SYSTEM_REFUND");
                }
            }

            // 2. 退还用户使用的优惠券 (恢复未使用，以供下次抵扣，人性化生产级设计)
            CouponUser couponUser = couponUserMapper.selectOne(new LambdaQueryWrapper<CouponUser>()
                    .eq(CouponUser::getOrderId, order.getId()));
            if (couponUser != null) {
                couponService.releaseCoupon(couponUser.getId(), refund.getUserId());
            }

            // 3. 扣除此前赠送的用户积分与会员成长值 (积分扣减)
            int refundScore = order.getTotalAmount().intValue();
            if (refundScore > 0) {
                memberService.deductPointsAndGrowth(refund.getUserId(), refundScore, refundScore);
            }

            // 4. 发送个人私信通知
            Notification notice = new Notification();
            notice.setTitle("售后退款已同意并退款成功");
            notice.setContent("商家已同意您的退款申请！金额 " + refund.getAmount() + " 元已成功原路退回。本次退款已同步退减您的会员积分和成长值。");
            notice.setType(1);
            notice.setReceiverId(refund.getUserId());
            notice.setCreatedAt(new Date());
            notificationMapper.insert(notice);

        } else if (status == 2) {
            // 拒绝退款
            refund.setStatus(2); // 已拒绝
            refund.setRejectReason(rejectReason);
            refund.setAuditTime(new Date());
            orderRefundMapper.updateById(refund);

            order.setStatus(7); // 拒绝退款 (退回到拒绝状态，或可根据实际流程退回发货/收货)
            orderMapper.updateById(order);

            // 发送通知告知拒绝
            Notification notice = new Notification();
            notice.setTitle("售后退款申请被拒绝");
            notice.setContent("您的退款申请已被商家拒绝！原因为: " + rejectReason + "。若有疑问请联系客服解决。");
            notice.setType(1);
            notice.setReceiverId(refund.getUserId());
            notice.setCreatedAt(new Date());
            notificationMapper.insert(notice);
        }
    }

    @Override
    public Logistics getLogistics(Long orderId) {
        return logisticsMapper.selectOne(new LambdaQueryWrapper<Logistics>().eq(Logistics::getOrderId, orderId));
    }
}
