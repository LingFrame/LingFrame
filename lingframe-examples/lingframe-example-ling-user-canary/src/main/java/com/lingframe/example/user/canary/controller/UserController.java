package com.lingframe.example.user.canary.controller;

import com.lingframe.example.user.canary.dto.UserDTO;
import com.lingframe.example.user.canary.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 用户控制器 - 金丝雀版本
 * 
 * 路由前缀：/user-canary
 * 包含实验性 API
 */
@Tag(name = "User Canary", description = "用户管理 API (金丝雀版本)")
@RequiredArgsConstructor
@RestController
@RequestMapping("/user-canary")
public class UserController {

    private final UserService userService;

    @Operation(summary = "查询用户")
    @GetMapping("/queryUser")
    public UserDTO queryUser(@RequestParam String userId) {
        return userService.queryUser(userId).orElse(null);
    }

    @Operation(summary = "列出所有用户")
    @GetMapping("/listUsers")
    public List<UserDTO> listUsers() {
        return userService.listUsers();
    }

    @Operation(summary = "创建用户")
    @PostMapping("/createUser")
    public UserDTO createUser(@RequestParam String name, @RequestParam String email) {
        return userService.createUser(name, email);
    }

    @Operation(summary = "更新用户")
    @PostMapping("/updateUser")
    public UserDTO updateUser(@RequestParam String id, @RequestParam String name, @RequestParam String email) {
        return userService.updateUser(id, name, email);
    }

    @Operation(summary = "删除用户")
    @PostMapping("/deleteUser/{id}")
    public boolean deleteUser(@PathVariable String id) {
        return userService.deleteUser(id);
    }

    @Operation(summary = "保存用户")
    @PostMapping("/saveUser")
    public void saveUser(@RequestBody UserDTO userDTO) {
        userService.saveUser(userDTO);
    }

    // ==================== 金丝雀版本新增 API ====================

    @Operation(summary = "批量查询用户 (实验性)")
    @PostMapping("/batchQueryUsers")
    public List<UserDTO> batchQueryUsers(@RequestBody List<String> userIds) {
        return userService.batchQueryUsers(userIds);
    }

    @Operation(summary = "更新用户状态 (实验性)")
    @PostMapping("/updateStatus")
    public boolean updateUserStatus(@RequestParam String userId, @RequestParam String status) {
        return userService.updateUserStatus(userId, status);
    }
}
