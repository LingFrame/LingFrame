package com.lingframe.starter.web;

import lombok.extern.slf4j.Slf4j;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Web 接口动态管理器
 * 职责：
 * 1. 动态将插件的 URL 注册到宿主 Spring MVC
 * 2. 处理精确/模糊路由匹配
 * 3. 插件卸载时彻底清理路由，防止内存泄漏
 */
@Slf4j
public class WebInterfaceManager {

    private static WebInterfaceManager INSTANCE;

    // 拆分 Exact Map 和 Ant Pattern Map
    private final Map<String, WebInterfaceMetadata> exactRouteMap = new ConcurrentHashMap<>();
    private final Map<String, WebInterfaceMetadata> antPatternMap = new ConcurrentHashMap<>();
    private final Map<String, WebInterfaceMetadata> routeMap = new ConcurrentHashMap<>();
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    private RequestMappingHandlerMapping hostMapping;
    private Object proxyController;
    private Method proxyMethod;

    public WebInterfaceManager() {
        INSTANCE = this;
    }

    public static WebInterfaceManager getInstance() {
        return INSTANCE;
    }

    // 初始化方法，由 AutoConfiguration 调用
    public void init(RequestMappingHandlerMapping mapping, Object controller, Method method) {
        this.hostMapping = mapping;
        this.proxyController = controller;
        this.proxyMethod = method;
    }

    public void register(WebInterfaceMetadata metadata) {
        if (hostMapping == null) {
            log.warn("WebInterfaceManager not initialized, skipping registration: {}", metadata.getUrlPattern());
            return;
        }

        String url = metadata.getUrlPattern();
        routeMap.put(url, metadata);

        // 拆分存储
        if (url.contains("*") || url.contains("?") || url.contains("{")) {
            antPatternMap.put(url, metadata);
        } else {
            exactRouteMap.put(url, metadata);
        }

        try {
            // 动态注册到宿主 Spring MVC
            RequestMappingInfo info = RequestMappingInfo
                    .paths(url)
                    .methods(RequestMethod.valueOf(metadata.getHttpMethod()))
                    .build();

            // 核心魔法：将所有插件 URL 映射到同一个 Proxy 方法上
            hostMapping.registerMapping(info, proxyController, proxyMethod);

            log.info("🌍 [LingFrame Web] Mapped: {} -> {}.{}", url, metadata.getPluginId(), metadata.getTargetMethod().getName());
        } catch (Exception e) {
            log.error("Failed to register web mapping: {}", url, e);
        }
    }

    /**
     * 注销插件的所有接口
     * 解决内存泄漏和路由冲突的关键
     */
    public void unregister(String pluginId) {
        if (hostMapping == null) return;

        log.info("♻️ [LingFrame Web] Unregistering interfaces for plugin: {}", pluginId);

        //  找出该插件所有的 URL
        List<String> urlsToRemove = new ArrayList<>();
        routeMap.forEach((url, meta) -> {
            if (meta.getPluginId().equals(pluginId)) {
                urlsToRemove.add(url);

                // 从 Spring MVC 核心中注销路由
                try {
                    RequestMappingInfo info = buildMappingInfo(url, meta.getHttpMethod());
                    hostMapping.unregisterMapping(info);
                } catch (Exception e) {
                    log.warn("Failed to unregister spring mapping for: {}", url, e);
                }
            }
        });

        // 从本地缓存中移除
        for (String url : urlsToRemove) {
            routeMap.remove(url);
            exactRouteMap.remove(url);
            antPatternMap.remove(url);
        }
    }

    public WebInterfaceMetadata match(String path) {
        // 优先走精确匹配（ConcurrentHashMap.get 是 O(1)）
        WebInterfaceMetadata meta = exactRouteMap.get(path);
        if (meta != null) return meta;

        // 只有没匹配到，才遍历 Ant Pattern Map (O(N))
        // 通常 Ant Pattern 的数量远少于总接口数
        for (Map.Entry<String, WebInterfaceMetadata> entry : antPatternMap.entrySet()) {
            if (pathMatcher.match(entry.getKey(), path)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private RequestMappingInfo buildMappingInfo(String url, String httpMethod) {
        return RequestMappingInfo
                .paths(url)
                .methods(RequestMethod.valueOf(httpMethod))
                .build();
    }
}