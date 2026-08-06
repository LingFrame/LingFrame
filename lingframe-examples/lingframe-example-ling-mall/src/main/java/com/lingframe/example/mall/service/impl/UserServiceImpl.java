package com.lingframe.example.mall.service.impl;

import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.lingframe.example.mall.dto.LoginRequest;
import com.lingframe.example.mall.dto.RegisterRequest;
import com.lingframe.example.mall.entity.Role;
import com.lingframe.example.mall.entity.SocialUser;
import com.lingframe.example.mall.entity.User;
import com.lingframe.example.mall.entity.UserRole;
import com.lingframe.example.mall.mapper.*;
import com.lingframe.example.mall.security.JwtUtils;
import com.lingframe.example.mall.security.LoginUser;
import com.lingframe.example.mall.service.MemberService;
import com.lingframe.example.mall.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements UserService {

    private final UserMapper userMapper;
    private final RoleMapper roleMapper;
    private final UserRoleMapper userRoleMapper;
    private final SocialUserMapper socialUserMapper;
    private final MenuMapper menuMapper;
    private final MemberService memberService;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtils jwtUtils;

    @Override
    public String login(LoginRequest loginRequest) {
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, loginRequest.getUsername()));
        
        if (user == null) {
            throw new IllegalArgumentException("用户名或密码错误");
        }
        
        if (user.getStatus() != 1) {
            throw new IllegalArgumentException("账户已被禁用");
        }

        if (!passwordEncoder.matches(loginRequest.getPassword(), user.getPassword())) {
            throw new IllegalArgumentException("用户名或密码错误");
        }

        return createTokenForUser(user);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void register(RegisterRequest registerRequest) {
        Long count = userMapper.selectCount(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, registerRequest.getUsername()));
        if (count > 0) {
            throw new IllegalArgumentException("用户名已被占用");
        }

        User user = new User();
        user.setUsername(registerRequest.getUsername());
        user.setPassword(passwordEncoder.encode(registerRequest.getPassword()));
        user.setNickname(registerRequest.getNickname());
        user.setPhone(registerRequest.getPhone());
        user.setEmail(registerRequest.getEmail());
        user.setStatus(1);
        user.setCreatedAt(new Date());
        userMapper.insert(user);

        // 默认分配普通用户角色 (ROLE_USER, id=2)
        UserRole userRole = new UserRole();
        userRole.setUserId(user.getId());
        userRole.setRoleId(2L);
        userRoleMapper.insert(userRole);

        // 初始化会员卡
        memberService.initMember(user.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String socialLogin(String platform, String openId, String nickname, String avatar) {
        SocialUser socialUser = socialUserMapper.selectOne(new LambdaQueryWrapper<SocialUser>()
                .eq(SocialUser::getPlatform, platform)
                .eq(SocialUser::getOpenId, openId));

        User user;
        if (socialUser == null) {
            String username = "oauth_" + platform + "_" + IdUtil.simpleUUID().substring(0, 8);
            user = new User();
            user.setUsername(username);
            user.setPassword(passwordEncoder.encode(IdUtil.fastSimpleUUID()));
            user.setNickname(nickname);
            user.setStatus(1);
            user.setCreatedAt(new Date());
            userMapper.insert(user);

            // 绑定角色
            UserRole userRole = new UserRole();
            userRole.setUserId(user.getId());
            userRole.setRoleId(2L);
            userRoleMapper.insert(userRole);

            // 初始化会员卡
            memberService.initMember(user.getId());

            // 记录社交绑定
            socialUser = new SocialUser();
            socialUser.setPlatform(platform);
            socialUser.setOpenId(openId);
            socialUser.setUserId(user.getId());
            socialUser.setNickname(nickname);
            socialUser.setAvatar(avatar);
            socialUser.setCreatedAt(new Date());
            socialUserMapper.insert(socialUser);
        } else {
            user = userMapper.selectById(socialUser.getUserId());
            if (user == null || user.getStatus() != 1) {
                throw new IllegalArgumentException("关联的本地账户异常或已被禁用");
            }
        }

        return createTokenForUser(user);
    }

    private String createTokenForUser(User user) {
        // 1. 获取角色 Code 列表 (例如 ROLE_ADMIN, ROLE_USER)
        List<Role> roles = roleMapper.selectRolesByUserId(user.getId());
        List<String> authorities = roles.stream().map(Role::getCode).collect(Collectors.toList());

        // 2. 获取细粒度的按钮菜单权限标识 (例如 product:admin:add)
        List<String> perms = menuMapper.selectPermsByUserId(user.getId());
        authorities.addAll(perms);

        LoginUser loginUser = new LoginUser(user.getId(), user.getUsername(), user.getPassword(), authorities);
        return jwtUtils.generateToken(loginUser);
    }
}
