package com.lingframe.example.user.controller;

import com.lingframe.example.user.api.UserService;
import com.lingframe.example.user.dto.UserDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RequiredArgsConstructor
@RestController
@RequestMapping("/user")
public class UserController {

    private final UserService userService;

    @GetMapping("/queryUser")
    public UserDTO queryUser(String userId) {
        return userService.queryUser(userId).orElse(null);
    }

    @GetMapping("/listUsers")
    public List<UserDTO> listUsers() {
        return userService.listUsers();
    }

    @PostMapping("/createUser")
    public UserDTO createUser(String name, String email) {
        return userService.createUser(name, email);
    }

    @PostMapping("/updateUser")
    public UserDTO updateUser(String id, String name, String email) {
        return userService.updateUser(id, name, email);
    }

    @PostMapping("/deleteUser/{id}")
    public boolean deleteUser(@PathVariable String id) {
        return userService.deleteUser(id);
    }

    @PostMapping("/saveUser")
    public void saveUser(@RequestBody UserDTO userDTO) {
        userService.saveUser(userDTO);
    }


}
