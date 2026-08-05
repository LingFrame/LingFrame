package com.lingframe.example.mall.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.lingframe.example.mall.entity.Member;

public interface MemberService extends IService<Member> {

    void initMember(Long userId);

    void addPointsAndGrowth(Long userId, Integer points, Integer growth);

    void deductPointsAndGrowth(Long userId, Integer points, Integer growth);
}
