package com.lingframe.example.order.controller;

import com.lingframe.example.order.dto.UserDTO;
import com.lingframe.example.order.service.OrderServiceImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RequiredArgsConstructor
@RestController
@RequestMapping("/order")
public class OrderController {

    private final OrderServiceImpl orderServiceImpl;

    @GetMapping("/user/{userId}")
    public UserDTO getUserById(@PathVariable Long userId) {
        return orderServiceImpl.getUserById(userId);
    }

    @GetMapping("/getFromHost")
    public void getFromHost() {
        orderServiceImpl.getFromHost();
    }
}
