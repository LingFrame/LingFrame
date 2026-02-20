package com.lingframe.example;

import com.lingframe.api.context.LingContext;
import com.lingframe.api.ling.Ling;
import com.lingframe.example.service.NativeService;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class NativeLing implements Ling {

    @Override
    public void onStart(LingContext context) {
        log.info("NativeLing onStart");
    }

    @Override
    public void onStop(LingContext context) {
        log.info("NativeLing onStop");
    }

    public static void main(String[] args) {
        NativeService nativeService = new NativeService();
        nativeService.sayHello();
    }
}
