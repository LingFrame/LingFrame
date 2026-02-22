package com.lingframe.example.service;

import com.lingframe.api.annotation.LingService;
import lombok.extern.slf4j.Slf4j;


@Slf4j
public class NativeService {

    @LingService(id = "nativeService", desc = "本地服务")
    public void sayHello() {
        log.info("NativeService sayHello");
    }
}
