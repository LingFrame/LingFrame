package com.lingframe.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class HostService {

    public String sayHello() {
        return "Hello, Host!";
    }
}
