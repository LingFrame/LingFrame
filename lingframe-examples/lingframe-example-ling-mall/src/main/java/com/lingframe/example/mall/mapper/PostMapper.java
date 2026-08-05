package com.lingframe.example.mall.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lingframe.example.mall.entity.Post;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface PostMapper extends BaseMapper<Post> {
}
