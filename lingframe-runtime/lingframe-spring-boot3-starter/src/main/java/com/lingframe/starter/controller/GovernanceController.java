package com.lingframe.starter.controller;

import com.lingframe.api.config.GovernancePolicy;
import com.lingframe.core.governance.LocalGovernanceRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RequiredArgsConstructor
@RestController
@RequestMapping("/lingframe/api/governance")
public class GovernanceController {

    private final LocalGovernanceRegistry registry;

    /**
     * 更新动态补丁 (无需重启插件)
     */
    @PostMapping("/patch/{pluginId}")
    public String updatePatch(@PathVariable String pluginId, @RequestBody GovernancePolicy policy) {
        registry.updatePatch(pluginId, policy);
        return "Patch applied successfully";
    }

    /**
     * 获取指定插件的动态补丁
     */
    @GetMapping("/patch/{pluginId}")
    public GovernancePolicy getPatch(@PathVariable String pluginId) {
        return registry.getPatch(pluginId);
    }

    /**
     * 获取当前生效的所有动态规则
     */
    @GetMapping("/rules")
    public Map<String, GovernancePolicy> getRules() {
        return registry.getAllPatches();
    }
}