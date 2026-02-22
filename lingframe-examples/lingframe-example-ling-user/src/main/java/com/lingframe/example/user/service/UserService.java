package com.lingframe.example.user.service;

import com.lingframe.example.user.dto.UserDTO;

import java.util.List;
import java.util.Optional;

public interface UserService {

    Optional<UserDTO> queryUser(String userId);

    List<UserDTO> listUsers();

    UserDTO createUser(String name, String email);

    UserDTO updateUser(String id, String name, String email);

    boolean deleteUser(String id);

    void saveUser(UserDTO userDTO);
}
