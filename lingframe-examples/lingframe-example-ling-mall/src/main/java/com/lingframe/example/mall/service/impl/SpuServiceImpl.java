package com.lingframe.example.mall.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.lingframe.example.mall.entity.Spu;
import com.lingframe.example.mall.mapper.SpuMapper;
import com.lingframe.example.mall.service.SpuService;
import org.springframework.stereotype.Service;

@Service
public class SpuServiceImpl extends ServiceImpl<SpuMapper, Spu> implements SpuService {
}
