package com.lingframe.example;

import com.lingframe.api.context.PluginContext;
import com.lingframe.api.plugin.LingPlugin;
import com.lingframe.example.service.NativeService;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class NativePlugin implements LingPlugin {

    @Override
    public void onStart(PluginContext context) {
        log.info("NativePlugin onStart");
    }

    @Override
    public void onStop(PluginContext context) {
        log.info("NativePlugin onStop");
    }

    public static void main(String[] args) {
        NativeService nativeService = new NativeService();
        nativeService.sayHello();
    }
}
