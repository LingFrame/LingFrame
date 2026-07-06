package com.lingframe.example.test.service;

import com.lingframe.example.test.dto.TestDTO;

import java.util.List;
import java.util.Optional;

public interface TestService {

    Optional<TestDTO> queryUser(String userId);

    List<TestDTO> listUsers();

    TestDTO createUser(String name, String email);

    TestDTO updateUser(String id, String name, String email);

    boolean deleteUser(String id);

    void saveUser(TestDTO userDTO);
}
