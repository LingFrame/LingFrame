package com.lingframe.example.mall.controller.portal;

import com.lingframe.example.mall.dto.CartItemDTO;
import com.lingframe.example.mall.dto.ResponseResult;
import com.lingframe.example.mall.security.SecurityUtils;
import com.lingframe.example.mall.service.CartService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "3. 购物车接口", description = "加购 SKU 型号、获取购物车内容与清空")
@RestController
@RequestMapping("/api/portal/cart")
@RequiredArgsConstructor
public class CartController {

    private final CartService cartService;

    @Operation(summary = "商品加购", description = "将指定SKU型号和数量加购至本地购物车")
    @PostMapping("/add")
    public ResponseResult<Void> addCart(@RequestParam Long skuId, @RequestParam Integer quantity) {
        Long userId = SecurityUtils.getUserId();
        cartService.addCart(userId, skuId, quantity);
        return ResponseResult.success();
    }

    @Operation(summary = "获取购物车", description = "获取当前用户购物车列表")
    @GetMapping("/get")
    public ResponseResult<List<CartItemDTO>> getCart() {
        Long userId = SecurityUtils.getUserId();
        List<CartItemDTO> list = cartService.getCart(userId);
        return ResponseResult.success(list);
    }

    @Operation(summary = "更新购物车数量", description = "直接更新购物车中某SKU的订购数量")
    @PutMapping("/update")
    public ResponseResult<Void> updateCart(@RequestParam Long skuId, @RequestParam Integer quantity) {
        Long userId = SecurityUtils.getUserId();
        cartService.updateCart(userId, skuId, quantity);
        return ResponseResult.success();
    }

    @Operation(summary = "移出购物车", description = "从购物车中彻底删除指定SKU商品")
    @DeleteMapping("/delete")
    public ResponseResult<Void> deleteCart(@RequestParam Long skuId) {
        Long userId = SecurityUtils.getUserId();
        cartService.deleteCart(userId, skuId);
        return ResponseResult.success();
    }

    @Operation(summary = "清空购物车", description = "清空当前用户全部购物车项目")
    @DeleteMapping("/clear")
    public ResponseResult<Void> clearCart() {
        Long userId = SecurityUtils.getUserId();
        cartService.clearCart(userId);
        return ResponseResult.success();
    }
}
