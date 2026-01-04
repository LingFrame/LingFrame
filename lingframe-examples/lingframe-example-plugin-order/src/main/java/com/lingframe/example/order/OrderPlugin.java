package com.lingframe.example.order;

import com.lingframe.api.context.PluginContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@Slf4j
@SpringBootApplication
public class OrderPlugin implements CommandLineRunner {

    @Autowired
    private PluginContext pluginContext;

    public static void main(String[] args) {
        SpringApplication.run(OrderPlugin.class, args);
    }

    @Override
    public void run(String... args) throws Exception {
        log.info("OrderPlugin started, pluginId: {}", pluginContext.getPluginId());
    }
}