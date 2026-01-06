package com.lingframe.example.order.service;

import com.lingframe.api.annotation.LingReference;
import com.lingframe.api.context.PluginContext;
import com.lingframe.example.order.api.OrderService;
import com.lingframe.example.order.dto.UserDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class OrderServiceImpl {

    @LingReference
    private OrderService orderService;

    @Autowired
    private PluginContext hostPluginContext;

    public UserDTO getUserById(Long userId) {
        log.info("getUserById, userId: {}", userId);
        UserDTO userDTO = orderService.getUserById(userId);
        log.info("getUserById, userDTO: {}", userDTO);
        return userDTO;
    }

    public void getFromHost() {
        log.info("从主机插件获取服务: {}", hostPluginContext.getPluginId());
    }
}
