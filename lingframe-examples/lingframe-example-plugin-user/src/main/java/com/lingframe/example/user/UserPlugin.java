package com.lingframe.example.user;

import com.lingframe.api.context.PluginContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@Slf4j
@SpringBootApplication
@RequiredArgsConstructor
public class UserPlugin implements CommandLineRunner {

    private final PluginContext pluginContext;

    public static void main(String[] args) {
        SpringApplication.run(UserPlugin.class, args);
    }

    @Override
    public void run(String... args) throws Exception {
        log.info("UserPlugin started, pluginId: {}", pluginContext.getPluginId());
    }
}