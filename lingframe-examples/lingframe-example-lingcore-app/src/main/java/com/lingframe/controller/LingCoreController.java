package com.lingframe.controller;

import com.lingframe.service.LingCoreService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Tag(name = "LINGCORE", description = "灵核应用接口")
@RestController
@RequestMapping("/lingcore")
@RequiredArgsConstructor
public class LingCoreController {

    private final LingCoreService hostService;

    @Operation(summary = "问候接口", description = "简单的 Hello World 测试接口，用于验证服务是否正常运行")
    @GetMapping("/hello")
    public String hello() {
        return hostService.sayHello();
    }

    @Operation(summary = "获取配置", description = "根据配置 Key 获取对应的配置值")
    @GetMapping("/config/{key}")
    public String getConfig(@PathVariable String key) {
        return hostService.getConfig(key);
    }

    @Operation(summary = "设置配置", description = "设置配置键值对，如果 Key 已存在则覆盖")
    @PostMapping("/config/{key}")
    public String setConfig(@PathVariable String key, @RequestParam String value) {
        return hostService.setConfig(key, value);
    }

    @Operation(summary = "删除配置", description = "根据 Key 删除对应的配置项")
    @DeleteMapping("/config/{key}")
    public boolean deleteConfig(@PathVariable String key) {
        return hostService.deleteConfig(key);
    }

    @Operation(summary = "获取所有配置", description = "列出当前内存中的所有配置项")
    @GetMapping("/configs")
    public List<Map<String, Object>> listConfigs() {
        return hostService.listConfigs();
    }
}
