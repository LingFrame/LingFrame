package com.lingframe.example.user.canary.service.impl;

import com.lingframe.api.annotation.Auditable;
import com.lingframe.example.order.api.UserQueryService;
import com.lingframe.example.order.dto.UserDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * 用户查询服务实现 - 金丝雀版本。
 * <p>
 * 实现与稳定版相同的 sharedapi 接口契约（{@link UserQueryService}），
 * 但返回不同的数据，用于验证多版本接口展示和按版本调用功能。
 */
@Slf4j
@Service
public class UserQueryServiceImpl implements UserQueryService {

    @Auditable(action = "findById", resource = "user-canary")
    @Override
    public Optional<UserDTO> findById(Long userId) {
        log.info("[Canary] findById, userId: {}", userId);
        UserDTO userDTO = new UserDTO();
        // 金丝雀版返回不同的数据，便于验证版本路由
        userDTO.setUserName("canary");
        userDTO.setAvatar("https://avatars.githubusercontent.com/u/2048?v=4");
        return Optional.of(userDTO);
    }
}
