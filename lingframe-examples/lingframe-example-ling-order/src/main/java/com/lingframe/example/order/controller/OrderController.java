package com.lingframe.example.order.controller;

import com.lingframe.example.order.dto.OrderDTO;
import com.lingframe.example.order.service.OrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "Order", description = "订单管理接口")
@RequiredArgsConstructor
@RestController
@RequestMapping("/order")
public class OrderController {

    private final OrderService orderService;

    @Operation(summary = "根据ID获取订单", description = "根据订单ID可以直接获取内存中的订单详情")
    @GetMapping("/{orderId}")
    public OrderDTO getOrderById(@PathVariable Long orderId) {
        return orderService.getOrderById(orderId);
    }

    @Operation(summary = "查询订单", description = "查询订单详情，返回值包含 Optional 包装")
    @GetMapping("/query/{orderId}")
    public OrderDTO queryOrder(@PathVariable Long orderId) {
        return orderService.queryOrder(orderId).orElse(null);
    }

    @Operation(summary = "获取订单列表", description = "列出所有已创建的订单")
    @GetMapping("/list")
    public List<OrderDTO> listOrders() {
        return orderService.listOrders();
    }

    @Operation(summary = "创建订单", description = "根据用户名创建一个新订单")
    @PostMapping("/create")
    public OrderDTO createOrder(@RequestParam String userName) {
        return orderService.createOrder(userName);
    }

    @Operation(summary = "删除订单", description = "根据订单ID删除指定订单")
    @DeleteMapping("/{orderId}")
    public boolean deleteOrder(@PathVariable Long orderId) {
        return orderService.deleteOrder(orderId);
    }
}
