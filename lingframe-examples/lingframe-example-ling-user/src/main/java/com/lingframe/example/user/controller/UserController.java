package com.lingframe.example.user.controller;

import com.lingframe.example.user.dto.UserDTO;
import com.lingframe.example.user.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "User", description = "用户管理接口")
@RequiredArgsConstructor
@RestController
@RequestMapping("/user")
public class UserController {

    private final UserService userService;

    @Operation(summary = "查询用户", description = "根据用户ID查询用户详情")
    @GetMapping("/queryUser")
    public UserDTO queryUser(String userId) {
        return userService.queryUser(userId).orElse(null);
    }

    @Operation(summary = "获取用户列表", description = "获取所有已注册的用户列表")
    @GetMapping("/listUsers")
    public List<UserDTO> listUsers() {
        return userService.listUsers();
    }

    @Operation(summary = "创建用户", description = "创建新用户，需要提供姓名和邮箱")
    @PostMapping("/createUser")
    public UserDTO createUser(String name, String email) {
        return userService.createUser(name, email);
    }

    @Operation(summary = "更新用户", description = "更新现有用户的信息")
    @PostMapping("/updateUser")
    public UserDTO updateUser(String id, String name, String email) {
        return userService.updateUser(id, name, email);
    }

    @Operation(summary = "删除用户", description = "根据用户ID删除用户")
    @PostMapping("/deleteUser/{id}")
    public boolean deleteUser(@PathVariable String id) {
        return userService.deleteUser(id);
    }

    @Operation(summary = "保存用户", description = "保存或更新用户对象")
    @PostMapping("/saveUser")
    public void saveUser(@RequestBody UserDTO userDTO) {
        userService.saveUser(userDTO);
    }
}
