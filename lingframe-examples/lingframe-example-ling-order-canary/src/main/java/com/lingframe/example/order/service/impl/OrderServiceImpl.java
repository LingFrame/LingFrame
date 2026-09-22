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
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderServiceImpl implements OrderService {

    /** 金丝雀版本标记，写入日志与返回结果，便于灰度流量识别 */
    private static final String CANARY_TAG = "[CANARY]";

    @LingReference
    private UserQueryService userQueryService;

    private final JdbcTemplate jdbcTemplate;

    /**
     * 根据订单ID查询订单（DB读取 + IPC调用获取用户信息）
     */
    @LingService(id = "get_order", desc = "根据ID查询订单")
    @RequiresPermission(Capabilities.STORAGE_SQL) // DB 读取
    public OrderDTO getOrderById(Long orderId) {
        log.info("{} getOrderById, orderId: {}", CANARY_TAG, orderId);
        try {
            OrderDTO order = jdbcTemplate.queryForObject(
                    "SELECT * FROM t_order WHERE order_id = ?",
                    new BeanPropertyRowMapper<>(OrderDTO.class),
                    orderId);
            // 通过 IPC 获取用户信息（用订单上的 userName 关联查询）
            if (order != null && order.getUserName() != null) {
                userQueryService.findById(order.getOrderId()).ifPresent(
                        userDTO -> order.setUserName(userDTO.getUserName()));
                markCanary(order);
            }
            return order;
        } catch (Exception e) {
            log.warn("{} Order not found: {}", CANARY_TAG, orderId, e);
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
        log.info("{} queryOrder (cache miss), orderId: {}", CANARY_TAG, orderId);
        try {
            OrderDTO order = jdbcTemplate.queryForObject(
                    "SELECT * FROM t_order WHERE order_id = ?",
                    new BeanPropertyRowMapper<>(OrderDTO.class),
                    orderId);
            markCanary(order);
            return Optional.ofNullable(order);
        } catch (Exception e) {
            log.error("{} Order query failed.", CANARY_TAG, e);
            return Optional.empty();
        }
    }

    /**
     * 列出所有订单
     */
    public List<OrderDTO> listOrders() {
        log.info("{} listOrders", CANARY_TAG);
        List<OrderDTO> orders = jdbcTemplate.query(
                "SELECT * FROM t_order",
                new BeanPropertyRowMapper<>(OrderDTO.class));
        orders.forEach(this::markCanary);
        return orders;
    }

    /**
     * 创建订单（DB写入 + 缓存写入）
     */
    @LingService(id = "create_order", desc = "创建订单")
    @RequiresPermission(Capabilities.STORAGE_SQL) // DB 写入
    @CachePut(cacheNames = "orders", key = "#result.orderId")
    public OrderDTO createOrder(String userName) {
        log.info("{} createOrder, userName: {}", CANARY_TAG, userName);
        // 捕获自增主键，保证 @CachePut(key = "#result.orderId") 键非空且响应携带新订单 ID
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO t_order (user_name) VALUES (?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, userName);
            return ps;
        }, keyHolder);
        OrderDTO order = new OrderDTO();
        order.setOrderId(keyHolder.getKey() == null ? null : keyHolder.getKey().longValue());
        order.setUserName(userName);
        markCanary(order);
        return order;
    }

    /**
     * 删除订单（DB写入 + 缓存清除）
     */
    @LingService(id = "delete_order", desc = "删除订单")
    @RequiresPermission(Capabilities.STORAGE_SQL) // DB 写入
    @CacheEvict(cacheNames = "orders", key = "#orderId")
    public boolean deleteOrder(Long orderId) {
        log.info("{} deleteOrder, orderId: {}", CANARY_TAG, orderId);
        return jdbcTemplate.update("DELETE FROM t_order WHERE order_id = ?", orderId) > 0;
    }

    /**
     * 给返回结果打金丝雀标记，便于灰度流量识别命中实例
     */
    private void markCanary(OrderDTO order) {
        if (order != null) {
            order.setCanary(true);
        }
    }
}
