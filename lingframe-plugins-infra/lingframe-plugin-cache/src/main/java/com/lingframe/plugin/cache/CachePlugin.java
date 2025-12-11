package com.lingframe.plugin.cache;

import com.lingframe.api.context.PluginContext;
import com.lingframe.api.plugin.LingPlugin;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class CachePlugin implements LingPlugin {

    @Override
    public void onStart(PluginContext context) {
        System.out.println("Cache plugin started: " + context.getPluginId());
    }

    @Override
    public void onStop(PluginContext context) {
        System.out.println("Cache plugin stopped: " + context.getPluginId());
    }
}