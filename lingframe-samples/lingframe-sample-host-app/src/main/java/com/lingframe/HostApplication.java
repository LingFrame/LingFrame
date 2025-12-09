package com.lingframe;

import com.lingframe.core.config.LingFrameConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class HostApplication {
    public static void main(String[] args) {
        // 【关键】开启开发模式：权限不足时仅警告，不报错
        LingFrameConfig.setDevMode(true);

        SpringApplication.run(HostApplication.class, args);
    }
}
