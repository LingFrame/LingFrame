package com.lingframe.example.user.service.impl;

import com.lingframe.api.annotation.Auditable;
import com.lingframe.example.order.api.UserQueryService;
import com.lingframe.example.order.dto.UserDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Slf4j
@Service
public class UserQueryServiceImpl implements UserQueryService {

    @Auditable(action = "findById", resource = "user")
    @Override
    public Optional<UserDTO> findById(Long userId) {
        log.info("findById, userId: {}", userId);
        UserDTO userDTO = new UserDTO();
        userDTO.setUserName("test");
        userDTO.setAvatar("https://avatars.githubusercontent.com/u/1024?v=4");
        return Optional.of(userDTO);
    }
}
