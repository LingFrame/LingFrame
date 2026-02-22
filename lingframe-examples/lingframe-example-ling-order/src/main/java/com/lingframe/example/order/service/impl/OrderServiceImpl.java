package com.lingframe.example.order.service.impl;

import com.lingframe.api.annotation.LingReference;
import com.lingframe.api.annotation.LingService;
import com.lingframe.api.annotation.RequiresPermission;
import com.lingframe.api.security.Capabilities;
import com.lingframe.example.order.api.UserQueryService;
import com.lingframe.example.order.dto.OrderDTO;
import com.lingframe.example.order.service.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderServiceImpl implements OrderService {

    @LingReference
    private UserQueryService userQueryService;

    private final JdbcTemplate jdbcTemplate;

    /**
     * 根据订单ID查询订单（DB读取 + IPC调用获取用户信息）
     */
    @LingService(id = "get_order", desc = "根据ID查询订单")
    @RequiresPermission(Capabilities.STORAGE_SQL) // DB 读取
    public OrderDTO getOrderById(Long orderId) {
        log.info("getOrderById, orderId: {}", orderId);
        try {
            OrderDTO order = jdbcTemplate.queryForObject(
                    "SELECT * FROM t_order WHERE order_id = ?",
                    new BeanPropertyRowMapper<>(OrderDTO.class),
                    orderId);
            // 通过 IPC 获取用户信息
            if (order != null) {
                userQueryService.findById(orderId).ifPresent(
                        userDTO -> order.setUserName(userDTO.getUserName()));
            }
            return order;
        } catch (Exception e) {
            log.warn("Order not found: {}", orderId, e);
            return null;
        }
    }

    /**
     * 查询订单（DB读取 + 缓存读取）
     */
    @LingService(id = "query_order", desc = "查询订单（带缓存）")
    @RequiresPermission(Capabilities.CACHE_LOCAL) // 缓存读取
    @Cacheable(cacheNames = "orders", key = "#orderId")
    public Optional<OrderDTO> queryOrder(Long orderId) {
        log.info("queryOrder (cache miss), orderId: {}", orderId);
        try {
            OrderDTO order = jdbcTemplate.queryForObject(
                    "SELECT * FROM t_order WHERE order_id = ?",
                    new BeanPropertyRowMapper<>(OrderDTO.class),
                    orderId);
            return Optional.ofNullable(order);
        } catch (Exception e) {
            log.error("Order query failed.", e);
            return Optional.empty();
        }
    }

    /**
     * 列出所有订单
     */
    public List<OrderDTO> listOrders() {
        log.info("listOrders");
        return jdbcTemplate.query(
                "SELECT * FROM t_order",
                new BeanPropertyRowMapper<>(OrderDTO.class));
    }

    /**
     * 创建订单（DB写入 + 缓存写入）
     */
    @LingService(id = "create_order", desc = "创建订单")
    @RequiresPermission(Capabilities.STORAGE_SQL) // DB 写入
    @CachePut(cacheNames = "orders", key = "#result.orderId")
    public OrderDTO createOrder(String userName) {
        log.info("createOrder, userName: {}", userName);
        jdbcTemplate.update("INSERT INTO t_order (user_name) VALUES (?)", userName);
        OrderDTO order = new OrderDTO();
        order.setUserName(userName);
        return order;
    }

    /**
     * 删除订单（DB写入 + 缓存清除）
     */
    @LingService(id = "delete_order", desc = "删除订单")
    @RequiresPermission(Capabilities.STORAGE_SQL) // DB 写入
    @CacheEvict(cacheNames = "orders", key = "#orderId")
    public boolean deleteOrder(Long orderId) {
        log.info("deleteOrder, orderId: {}", orderId);
        return jdbcTemplate.update("DELETE FROM t_order WHERE order_id = ?", orderId) > 0;
    }
}
