package com.lingframe.starter.web;

import com.lingframe.api.exception.LingException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.ServletWebRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link WebInterfaceManager} 内部 Handler 类补充测试。
 * <p>
 * 重点覆盖 {@link WebInterfaceManager.LingGatewayHandler} 与
 * {@link WebInterfaceManager.LingWebEntryHandler} 的构造、路由键持有与分发委派行为，
 * 这些在已有 {@code WebInterfaceManagerTest} 中未被直接覆盖。
 */
@DisplayName("WebInterfaceManager 内部 Handler 补充测试")
class WebInterfaceManagerSupplementTest {

    private WebInterfaceManager manager;

    @AfterEach
    void tearDown() {
        if (manager != null) {
            manager.shutdown();
        }
    }

    @Test
    @DisplayName("gatewayHandler 应返回持有 manager 的 LingGatewayHandler 实例")
    void shouldReturnGatewayHandlerInstance() {
        manager = new WebInterfaceManager(null, null);
        WebInterfaceManager.LingGatewayHandler handler = manager.gatewayHandler();

        assertNotNull(handler);
        // 验证 handler 持有同一个 manager（通过 dispatch 行为间接验证）
    }

    @Test
    @DisplayName("LingGatewayHandler.dispatch 在无可用路由时应抛出 LingException")
    void gatewayHandlerDispatchShouldThrowWhenNoRouteAvailable() {
        manager = new WebInterfaceManager(null, null);
        WebInterfaceManager.LingGatewayHandler handler = manager.gatewayHandler();

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/non-existent");
        MockHttpServletResponse response = new MockHttpServletResponse();
        ServletWebRequest webRequest = new ServletWebRequest(request, response);

        LingException ex = assertThrows(LingException.class, () -> handler.dispatch(webRequest));
        // 动态网关路由 ID 应为固定字符串
        assertTrue(ex.getMessage().contains("dynamic gateway"));
    }

    @Test
    @DisplayName("LingWebEntryHandler 应持有构造时传入的 routeKey")
    void entryHandlerShouldHoldRouteKey() {
        manager = new WebInterfaceManager(null, null);
        String routeKey = "GET#/ling-a/sample";
        WebInterfaceManager.LingWebEntryHandler handler =
                new WebInterfaceManager.LingWebEntryHandler(manager, routeKey);

        assertEquals(routeKey, handler.getRouteKey());
    }

    @Test
    @DisplayName("LingWebEntryHandler.dispatch 应委派到 manager.dispatch(routeKey, webRequest)")
    void entryHandlerDispatchShouldDelegateToManagerByRouteKey() {
        manager = new WebInterfaceManager(null, null);
        String routeKey = "GET#/ling-a/missing";
        WebInterfaceManager.LingWebEntryHandler handler =
                new WebInterfaceManager.LingWebEntryHandler(manager, routeKey);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/ling-a/missing");
        MockHttpServletResponse response = new MockHttpServletResponse();
        ServletWebRequest webRequest = new ServletWebRequest(request, response);

        // 该路由未注册，dispatchResolved 应抛出 LingException 并包含 routeKey
        LingException ex = assertThrows(LingException.class, () -> handler.dispatch(webRequest));
        assertTrue(ex.getMessage().contains(routeKey));
    }
}
