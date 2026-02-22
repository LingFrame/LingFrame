package com.lingframe.example.order.service;

import com.lingframe.example.order.dto.OrderDTO;

import java.util.List;
import java.util.Optional;

/**
 * 订单服务接口
 */
public interface OrderService {

    OrderDTO getOrderById(Long orderId);

    Optional<OrderDTO> queryOrder(Long orderId);

    List<OrderDTO> listOrders();

    OrderDTO createOrder(String userName);

    boolean deleteOrder(Long orderId);
}
