package com.lingframe.example.mall.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.lingframe.example.mall.entity.OrderRefund;
import com.lingframe.example.mall.mapper.OrderRefundMapper;
import com.lingframe.example.mall.service.OrderRefundService;
import org.springframework.stereotype.Service;

@Service
public class OrderRefundServiceImpl extends ServiceImpl<OrderRefundMapper, OrderRefund> implements OrderRefundService {
}
