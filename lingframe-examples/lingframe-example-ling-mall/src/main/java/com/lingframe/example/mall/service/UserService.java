package com.lingframe.example.mall.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.lingframe.example.mall.dto.LoginRequest;
import com.lingframe.example.mall.dto.RegisterRequest;
import com.lingframe.example.mall.entity.User;

public interface UserService extends IService<User> {

    String login(LoginRequest loginRequest);

    void register(RegisterRequest registerRequest);

    String socialLogin(String platform, String openId, String nickname, String avatar);
}
