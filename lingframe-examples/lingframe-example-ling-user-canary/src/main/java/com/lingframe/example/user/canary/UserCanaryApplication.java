package com.lingframe.example.user.canary;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 用户单元金丝雀版本启动类
 * 
 * 用于测试新功能和灰度发布场景
 */
@Slf4j
@SpringBootApplication
public class UserCanaryApplication {

    public static void main(String[] args) {
        log.info("Starting User ling Canary Version...");
        SpringApplication.run(UserCanaryApplication.class, args);
    }

}
