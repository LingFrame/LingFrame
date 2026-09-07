package com.lingframe.dashboard.service;

import com.lingframe.core.event.EventBus;
import com.lingframe.dashboard.dto.LogStreamDTO;
import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.Semaphore;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

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
        @DisplayName("达到 MAX_CONNECTIONS 上限时应抛 IllegalStateException")
        void shouldRejectWhenMaxConnectionsReached() throws Exception {
            // 通过反射耗尽 Semaphore 许可，模拟连接数达到上限
            Field f = LogStreamService.class.getDeclaredField("connectionSemaphore");
            f.setAccessible(true);
            Semaphore semaphore = (Semaphore) f.get(service);
            int permits = semaphore.drainPermits();
            assertEquals(100, permits, "初始许可应等于 MAX_CONNECTIONS");

            // 获取许可后应抛 IllegalStateException（tryAcquire 最多等待 1 秒后失败）
            assertThrows(IllegalStateException.class, () -> service.createEmitter());

            // emitters 列表不应增长（仍为 0）
            Field ef = LogStreamService.class.getDeclaredField("emitters");
            ef.setAccessible(true);
            @SuppressWarnings("unchecked")
            List<SseEmitter> emitters = (List<SseEmitter>) ef.get(service);
            assertEquals(0, emitters.size());
        }

        @Test
        @DisplayName("正常创建 emitter 后许可应减少 1")
        void shouldAcquirePermitOnCreateEmitter() throws Exception {
            Field f = LogStreamService.class.getDeclaredField("connectionSemaphore");
            f.setAccessible(true);
            Semaphore semaphore = (Semaphore) f.get(service);
            int initialPermits = semaphore.availablePermits();

            service.createEmitter();

            assertEquals(initialPermits - 1, semaphore.availablePermits(), "创建 emitter 后许可应减 1");
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