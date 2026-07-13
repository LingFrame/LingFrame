package com.lingframe.example.mall.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.lingframe.example.mall.entity.Member;
import com.lingframe.example.mall.entity.MemberLevel;
import com.lingframe.example.mall.entity.Notification;
import com.lingframe.example.mall.mapper.MemberLevelMapper;
import com.lingframe.example.mall.mapper.MemberMapper;
import com.lingframe.example.mall.mapper.NotificationMapper;
import com.lingframe.example.mall.service.MemberService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class MemberServiceImpl extends ServiceImpl<MemberMapper, Member> implements MemberService {

    private final MemberMapper memberMapper;
    private final MemberLevelMapper memberLevelMapper;
    private final NotificationMapper notificationMapper; // 用于升级时发布站内通知

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void initMember(Long userId) {
        Member member = new Member();
        member.setUserId(userId);
        member.setPoint(0);
        member.setGrowth(0);
        member.setVipLevel(0);
        memberMapper.insert(member);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void addPointsAndGrowth(Long userId, Integer points, Integer growth) {
        Member member = memberMapper.selectById(userId);
        if (member == null) {
            log.warn("Member info not found for userId: {}", userId);
            return;
        }

        int oldLevel = member.getVipLevel();
        member.setPoint(member.getPoint() + points);
        member.setGrowth(member.getGrowth() + growth);

        // 重新计算等级
        int newLevel = calculateLevel(member.getGrowth());
        member.setVipLevel(newLevel);
        memberMapper.updateById(member);

        // 若升级，发送站内通知
        if (newLevel > oldLevel) {
            MemberLevel levelConfig = memberLevelMapper.selectOne(new LambdaQueryWrapper<MemberLevel>()
                    .eq(MemberLevel::getVipLevel, newLevel));
            String levelName = levelConfig != null ? levelConfig.getName() : ("VIP" + newLevel);
            
            Notification notice = new Notification();
            notice.setTitle("会员等级升级通知");
            notice.setContent("恭喜！您的成长值已达到 " + member.getGrowth() + "，成功升级为【" + levelName + "】！" +
                    "您目前下单将享有 " + (levelConfig != null ? levelConfig.getDiscountRate().multiply(new java.math.BigDecimal("10")) : "10") + " 折专属特权优惠！");
            notice.setType(1); // 个人私信
            notice.setReceiverId(userId);
            notice.setCreatedAt(new Date());
            notificationMapper.insert(notice);
            
            log.info("User {} upgraded from VIP {} to VIP {}", userId, oldLevel, newLevel);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deductPointsAndGrowth(Long userId, Integer points, Integer growth) {
        Member member = memberMapper.selectById(userId);
        if (member == null) {
            return;
        }

        int oldLevel = member.getVipLevel();
        // 积分和成长值最低减到 0
        member.setPoint(Math.max(0, member.getPoint() - points));
        member.setGrowth(Math.max(0, member.getGrowth() - growth));

        // 重新计算等级
        int newLevel = calculateLevel(member.getGrowth());
        member.setVipLevel(newLevel);
        memberMapper.updateById(member);

        // 若降级，发送通知
        if (newLevel < oldLevel) {
            MemberLevel levelConfig = memberLevelMapper.selectOne(new LambdaQueryWrapper<MemberLevel>()
                    .eq(MemberLevel::getVipLevel, newLevel));
            String levelName = levelConfig != null ? levelConfig.getName() : ("VIP" + newLevel);

            Notification notice = new Notification();
            notice.setTitle("会员等级变更通知");
            notice.setContent("由于订单发生退款，扣减相应成长值。您目前的成长值为 " + member.getGrowth() + "，您的会员等级调整为【" + levelName + "】。");
            notice.setType(1);
            notice.setReceiverId(userId);
            notice.setCreatedAt(new Date());
            notificationMapper.insert(notice);
            
            log.info("User {} downgraded from VIP {} to VIP {}", userId, oldLevel, newLevel);
        }
    }

    private int calculateLevel(int growth) {
        // 加载所有会员等级，按需要成长值降序排列
        List<MemberLevel> levels = memberLevelMapper.selectList(new LambdaQueryWrapper<MemberLevel>()
                .orderByDesc(MemberLevel::getNeedGrowth));
        for (MemberLevel level : levels) {
            if (growth >= level.getNeedGrowth()) {
                return level.getVipLevel();
            }
        }
        return 0;
    }
}
