package com.lingframe.example.mall.service.impl;

import cn.hutool.json.JSONUtil;
import com.lingframe.example.mall.dto.CartItemDTO;
import com.lingframe.example.mall.entity.Sku;
import com.lingframe.example.mall.entity.Spu;
import com.lingframe.example.mall.service.CartService;
import com.lingframe.example.mall.service.SkuService;
import com.lingframe.example.mall.service.SpuService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
public class CartServiceImpl implements CartService {

    private final SkuService skuService;
    private final SpuService spuService;

    // 内存模拟购物车
    private static final Map<Long, List<CartItemDTO>> CART_MAP = new ConcurrentHashMap<>();

    @Override
    public void addCart(Long userId, Long skuId, Integer quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("商品数量必须大于0");
        }
        Sku sku = skuService.getById(skuId);
        if (sku == null || sku.getStatus() != 1) {
            throw new IllegalArgumentException("商品型号不存在或已下架");
        }
        Spu spu = spuService.getById(sku.getSpuId());
        if (spu == null || spu.getStatus() != 1) {
            throw new IllegalArgumentException("商品不存在或已下架");
        }

        List<CartItemDTO> items = CART_MAP.computeIfAbsent(userId, k -> new ArrayList<>());

        CartItemDTO existingItem = items.stream()
                .filter(item -> item.getProductId().equals(skuId)) // 购物车中的 productId 即 skuId
                .findFirst()
                .orElse(null);

        if (existingItem != null) {
            existingItem.setQuantity(existingItem.getQuantity() + quantity);
        } else {
            CartItemDTO newItem = new CartItemDTO();
            newItem.setProductId(skuId); // 用 skuId 充当 productId
            
            // 组合规格名，例如: iPhone 15 Pro Max (原色钛金属, 256G)
            Map<String, String> specs = JSONUtil.toBean(sku.getSpecsJson(), Map.class);
            String specsStr = String.join(", ", specs.values());
            newItem.setProductName(spu.getName() + " (" + specsStr + ")");
            
            newItem.setPrice(sku.getPrice());
            newItem.setQuantity(quantity);
            newItem.setImageUrl(sku.getImageUrl() != null ? sku.getImageUrl() : spu.getImageUrl());
            items.add(newItem);
        }
    }

    @Override
    public List<CartItemDTO> getCart(Long userId) {
        return CART_MAP.getOrDefault(userId, new ArrayList<>());
    }

    @Override
    public void updateCart(Long userId, Long skuId, Integer quantity) {
        if (quantity <= 0) {
            deleteCart(userId, skuId);
            return;
        }
        List<CartItemDTO> items = CART_MAP.get(userId);
        if (items != null) {
            items.stream()
                    .filter(item -> item.getProductId().equals(skuId))
                    .findFirst()
                    .ifPresent(item -> item.setQuantity(quantity));
        }
    }

    @Override
    public void deleteCart(Long userId, Long skuId) {
        List<CartItemDTO> items = CART_MAP.get(userId);
        if (items != null) {
            items.removeIf(item -> item.getProductId().equals(skuId));
        }
    }

    @Override
    public void clearCart(Long userId) {
        CART_MAP.remove(userId);
    }
}
