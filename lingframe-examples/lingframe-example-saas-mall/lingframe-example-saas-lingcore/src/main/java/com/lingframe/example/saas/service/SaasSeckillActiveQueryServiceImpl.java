package com.lingframe.example.saas.service;

import com.lingframe.example.mall.entity.SeckillActive;
import com.lingframe.example.mall.mapper.SeckillActiveMapper;
import com.lingframe.example.saas.api.SaasSeckillActiveQueryService;
import com.lingframe.example.saas.api.dto.SaasSeckillActive;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class SaasSeckillActiveQueryServiceImpl implements SaasSeckillActiveQueryService {

    // 引入底座原生的 MyBatis-Plus Mapper，保持原有技术栈高度统一！
    private final SeckillActiveMapper seckillActiveMapper;

    @Override
    public SaasSeckillActive getActiveById(Long activeId) {
        log.info("Querying seckill active info from database for active ID: {}", activeId);
        
        // 强类型 MyBatis-Plus 查询
        SeckillActive active = seckillActiveMapper.selectById(activeId);
        if (active == null) {
            return null;
        }

        // 转换并输出显性契约 DTO
        SaasSeckillActive saasActive = new SaasSeckillActive();
        saasActive.setId(active.getId());
        saasActive.setSkuId(active.getSkuId());
        saasActive.setStock(active.getStock());
        saasActive.setStartTime(active.getStartTime());
        saasActive.setEndTime(active.getEndTime());
        return saasActive;
    }
}
