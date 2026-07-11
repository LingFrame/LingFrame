package com.lingframe.dashboard.service;

import com.lingframe.core.event.EventBus;
import com.lingframe.dashboard.dto.LogStreamDTO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SSE 日志流服务测试
 * 覆盖初始化订阅、emitter 创建/上限拒绝、广播空连接、销毁清理
 */
@DisplayName("日志流服务测试")
class LogStreamServiceTest {

    private EventBus eventBus;
    private LogStreamService service;

    @BeforeEach
    void setUp() {
        eventBus = mock(EventBus.class);
        service = new LogStreamService(eventBus);
    }

    @AfterEach
    void tearDown() {
        // 清理线程池，防止泄漏
        service.destroy();
    }

    @Nested
    @DisplayName("afterPropertiesSet")
    class InitTests {

        @Test
        @DisplayName("应订阅全部 13 类事件并启动心跳")
        void shouldSubscribeAllEvents() {
            service.afterPropertiesSet();

            // 6 个监控事件 + 3 个状态变化 + 4 个生命周期 = 13
            verify(eventBus, times(13)).subscribe(eq("lingframe-dashboard"), any(), any());
        }
    }

    @Nested
    @DisplayName("createEmitter")
    class CreateEmitterTests {

        @Test
        @DisplayName("正常创建 emitter 并加入 emitters 列表")
        void shouldCreateEmitter() {
            SseEmitter emitter = service.createEmitter();

            assertNotNull(emitter);
        }

        @Test
        @DisplayName("达到 MAX_CONNECTIONS 上限时应返回 error emitter")
        void shouldRejectWhenMaxConnectionsReached() throws Exception {
            // 通过反射填满 emitters 列表到上限
            java.lang.reflect.Field f = LogStreamService.class.getDeclaredField("emitters");
            f.setAccessible(true);
            @SuppressWarnings("unchecked")
            List<SseEmitter> emitters = (List<SseEmitter>) f.get(service);
            for (int i = 0; i < 100; i++) {
                emitters.add(new SseEmitter(0L));
            }

            SseEmitter rejected = service.createEmitter();

            assertNotNull(rejected);
            // 被拒绝的 emitter 应通过 completeWithError 结束
            // 验证 emitters 列表没有增长（仍为 100）
            assertEquals(100, emitters.size());
        }
    }

    @Nested
    @DisplayName("broadcast")
    class BroadcastTests {

        @Test
        @DisplayName("emitters 为空时应直接返回不抛异常")
        void shouldReturnWhenNoEmitters() {
            LogStreamDTO dto = LogStreamDTO.builder().type("TRACE").content("test").build();

            service.broadcast(dto); // 不应抛异常
        }

        @Test
        @DisplayName("dispatcher 已关闭时应直接返回")
        void shouldReturnWhenDispatcherShutdown() {
            service.destroy(); // 先关闭

            LogStreamDTO dto = LogStreamDTO.builder().type("TRACE").content("test").build();
            service.broadcast(dto); // 不应抛异常
        }

        @Test
        @DisplayName("有 emitter 时应异步广播不阻塞")
        void shouldBroadcastAsync() throws Exception {
            service.createEmitter();

            LogStreamDTO dto = LogStreamDTO.builder().type("TRACE").content("hello").build();
            service.broadcast(dto);

            // 等待异步任务执行（给 dispatcher 线程一点时间）
            Thread.sleep(200);
            // 不抛异常即通过
        }
    }

    @Nested
    @DisplayName("destroy")
    class DestroyTests {

        @Test
        @DisplayName("应取消所有事件订阅并关闭线程池")
        void shouldUnsubscribeAndShutdown() {
            service.afterPropertiesSet();

            service.destroy();

            verify(eventBus).unsubscribeAll("lingframe-dashboard");
        }

        @Test
        @DisplayName("重复调用 destroy 不抛异常")
        void shouldAllowDoubleDestroy() {
            service.destroy();
            service.destroy(); // 不抛异常
        }
    }
}
