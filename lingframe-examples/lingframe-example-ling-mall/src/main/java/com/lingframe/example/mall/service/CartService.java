package com.lingframe.example.mall.service;

import com.lingframe.example.mall.dto.CartItemDTO;

import java.util.List;

public interface CartService {
    
    void addCart(Long userId, Long skuId, Integer quantity);

    List<CartItemDTO> getCart(Long userId);

    void updateCart(Long userId, Long skuId, Integer quantity);

    void deleteCart(Long userId, Long skuId);

    void clearCart(Long userId);
}
