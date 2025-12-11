package com.lingframe.sample.user;

import com.lingframe.api.context.PluginContext;
import com.lingframe.api.plugin.LingPlugin;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class UserPlugin implements LingPlugin {

    @Override
    public void onStart(PluginContext context) {
        System.out.println("User plugin started: " + context.getPluginId());
    }

    @Override
    public void onStop(PluginContext context) {
        System.out.println("User plugin stopped: " + context.getPluginId());
    }
}