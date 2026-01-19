package com.lingframe.example.order.service;

import com.lingframe.api.annotation.LingReference;
import com.lingframe.example.order.api.UserQueryService;
import com.lingframe.example.order.dto.OrderDTO;
import com.lingframe.example.order.dto.UserDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class OrderServiceImpl {

    @LingReference
    private UserQueryService userQueryService;

    public OrderDTO getOrderById(Long userId) {
        log.info("getOrderById, userId: {}", userId);
        OrderDTO orderDTO = new OrderDTO();
        UserDTO userDTO = userQueryService.findById(userId).orElse(null);
        log.info("getOrderById, userDTO: {}", userDTO);
        if (userDTO != null) {
            orderDTO.setUserName(userDTO.getUserName());
        }
        return orderDTO;
    }

}
