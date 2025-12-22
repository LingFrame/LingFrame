package com.lingframe.example.user.service;

import com.lingframe.api.annotation.Auditable;
import com.lingframe.example.order.api.OrderService;
import com.lingframe.example.order.dto.UserDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class UserOrderService implements OrderService {

    @Auditable(action = "getUserById", resource = "user")
    @Override
    public UserDTO getUserById(Long userId) {
        log.info("getUserById, userId: {}", userId);
        UserDTO userDTO = new UserDTO();
        userDTO.setUserName("test");
        userDTO.setAvatar("https://avatars.githubusercontent.com/u/1024?v=4");
        return userDTO;
    }
}
