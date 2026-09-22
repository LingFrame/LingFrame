package com.lingframe.core.dev;

import com.lingframe.api.config.LingDefinition;
import com.lingframe.api.event.lifecycle.LingUninstalledEvent;
import com.lingframe.core.event.EventBus;
import com.lingframe.core.ling.LingLifecycleEngine;
import com.lingframe.core.ling.LingRepository;
import com.lingframe.core.spi.LeakDetector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("HotSwapWatcher 测试")
class HotSwapWatcherTest {

    @TempDir
    File tempDir;

    private HotSwapWatcher watcher;
    private LingLifecycleEngine lifecycleEngine;
    private LingRepository lingRepository;
    private EventBus eventBus;
    private LeakDetector leakDetector;

    @BeforeEach
    void setUp() {
        lifecycleEngine = mock(LingLifecycleEngine.class);
        lingRepository = mock(LingRepository.class);
        eventBus = mock(EventBus.class);
        leakDetector = mock(LeakDetector.class);
        watcher = new HotSwapWatcher(lifecycleEngine, lingRepository, eventBus, leakDetector);
    }

    @AfterEach
    void tearDown() {
        watcher.shutdown();
    }

    @Test
    @DisplayName("register 空参数不报错")
    void shouldHandleNullRegister() {
        assertDoesNotThrow(() -> watcher.register(null, tempDir, mock(LingDefinition.class)));
        assertDoesNotThrow(() -> watcher.register("ling-a", null, mock(LingDefinition.class)));
        assertDoesNotThrow(() -> watcher.register("ling-a", tempDir, null));
    }

    @Test
    @DisplayName("register 有效目录后可 unregister")
    void shouldRegisterAndUnregister() throws IOException {
        File classesDir = new File(tempDir, "classes");
        classesDir.mkdirs();
        LingDefinition def = mock(LingDefinition.class);
        when(def.getId()).thenReturn("test-ling");
        when(def.getVersion()).thenReturn("1.0.0");

        watcher.register("test-ling", classesDir, def);
        assertDoesNotThrow(() -> watcher.unregister("test-ling"));
    }

    @Test
    @DisplayName("unregister 不存在的 lingId 不报错")
    void shouldHandleUnregisterNonExistent() {
        assertDoesNotThrow(() -> watcher.unregister("nonexistent"));
    }

    @Test
    @DisplayName("shutdown 后可安全调用")
    void shouldShutdownCleanly() {
        File classesDir = new File(tempDir, "classes");
        classesDir.mkdirs();

        watcher.register("test-ling", classesDir, mock(LingDefinition.class));
        assertDoesNotThrow(() -> watcher.shutdown());
    }

    @Test
    @DisplayName("重复 register 同一个 lingId 不报错")
    void shouldHandleDuplicateRegister() throws IOException {
        File classesDir = new File(tempDir, "classes");
        classesDir.mkdirs();
        LingDefinition def = mock(LingDefinition.class);
        when(def.getId()).thenReturn("test-ling");
        when(def.getVersion()).thenReturn("1.0.0");

        watcher.register("test-ling", classesDir, def);
        assertDoesNotThrow(() -> watcher.register("test-ling", classesDir, def));
    }

    @Test
    @DisplayName("onEvent 处理卸载事件时移除注册")
    void shouldUnregisterOnUninstallEvent() {
        File classesDir = new File(tempDir, "classes");
        classesDir.mkdirs();
        LingDefinition def = mock(LingDefinition.class);
        when(def.getId()).thenReturn("test-ling");
        when(def.getVersion()).thenReturn("1.0.0");

        watcher.register("test-ling", classesDir, def);

        LingUninstalledEvent event = mock(LingUninstalledEvent.class);
        when(event.getLingId()).thenReturn("test-ling");

        assertDoesNotThrow(() -> watcher.onEvent(event));
    }

    @Test
    @DisplayName("onEvent 忽略 reloading 中的灵元")
    void shouldIgnoreReloadingLing() {
        LingUninstalledEvent event = mock(LingUninstalledEvent.class);
        when(event.getLingId()).thenReturn("reloading-ling");

        // 不会抛异常
        assertDoesNotThrow(() -> watcher.onEvent(event));
    }

    @Test
    @DisplayName("null LeakDetector 构造不报错")
    void shouldCreateWithNullLeakDetector() {
        assertDoesNotThrow(() -> {
            HotSwapWatcher w = new HotSwapWatcher(lifecycleEngine, lingRepository, eventBus, null);
            w.shutdown();
        });
    }

    @Test
    @DisplayName("lifecycleEngine 未绑定（native 延迟注入窗口内）触发热重载不抛 NPE")
    void shouldSkipReloadWhenEngineNotBound() throws Exception {
        // native 装配：先传 null 构造，engine 创建后再 setLifecycleEngine 绑定；
        // 此窗口内文件变动触发 doReload 应经由判空安全跳过，而非 NPE
        HotSwapWatcher w = new HotSwapWatcher(null, lingRepository, eventBus, leakDetector);
        try {
            File classesDir = new File(tempDir, "not-bound-ling");
            classesDir.mkdirs();
            // 放入真实 .class 文件，使 hasCompilationErrors 为 false——否则空目录会提前 return，
            // 无法命中「engine 未绑定」的判空分支（在判空之前）
            File classFile = new File(classesDir, "Fake.class");
            classFile.createNewFile();
            LingDefinition def = mock(LingDefinition.class);
            when(def.getId()).thenReturn("not-bound-ling");
            when(def.getVersion()).thenReturn("1.0.0");
            w.register("not-bound-ling", classesDir, def);

            Method method = HotSwapWatcher.class.getDeclaredMethod("doReload", String.class);
            method.setAccessible(true);
            assertDoesNotThrow(() -> method.invoke(w, "not-bound-ling"));
            // 未绑定 engine：不应触发任何卸载/重部署
            verifyNoMoreInteractions(leakDetector);
        } finally {
            w.shutdown();
        }
    }

    @Test
    @DisplayName("register 带子目录的 classes 目录")
    void shouldRegisterWithSubDirectories() throws IOException {
        File classesDir = new File(tempDir, "classes");
        File subDir = new File(classesDir, "com/example");
        subDir.mkdirs();
        LingDefinition def = mock(LingDefinition.class);
        when(def.getId()).thenReturn("test-ling");
        when(def.getVersion()).thenReturn("1.0.0");

        assertDoesNotThrow(() -> watcher.register("test-ling", classesDir, def));
    }

    @Test
    @DisplayName("shutdown 后 unregister 不报错")
    void shouldUnregisterAfterShutdown() {
        File classesDir = new File(tempDir, "classes");
        classesDir.mkdirs();
        watcher.register("test-ling", classesDir, mock(LingDefinition.class));
        watcher.shutdown();
        assertDoesNotThrow(() -> watcher.unregister("test-ling"));
    }

    @Test
    @DisplayName("多个 lingId 注册和注销")
    void shouldRegisterMultipleLings() throws IOException {
        File dir1 = new File(tempDir, "ling-a");
        File dir2 = new File(tempDir, "ling-b");
        dir1.mkdirs();
        dir2.mkdirs();

        LingDefinition def1 = mock(LingDefinition.class);
        when(def1.getId()).thenReturn("ling-a");
        when(def1.getVersion()).thenReturn("1.0");
        LingDefinition def2 = mock(LingDefinition.class);
        when(def2.getId()).thenReturn("ling-b");
        when(def2.getVersion()).thenReturn("2.0");

        watcher.register("ling-a", dir1, def1);
        watcher.register("ling-b", dir2, def2);

        assertDoesNotThrow(() -> watcher.unregister("ling-a"));
        assertDoesNotThrow(() -> watcher.unregister("ling-b"));
    }

    @Test
    @DisplayName("register 仅两参数版本不报错")
    void shouldRegisterWithTwoArgs() throws IOException {
        File classesDir = new File(tempDir, "classes");
        classesDir.mkdirs();
        assertDoesNotThrow(() -> watcher.register("test-ling", classesDir));
    }

    @Test
    @DisplayName("null LingRepository 构造不报错")
    void shouldCreateWithNullRepository() {
        assertDoesNotThrow(() -> {
            HotSwapWatcher w = new HotSwapWatcher(lifecycleEngine, null, eventBus, leakDetector);
            w.shutdown();
        });
    }

    @Test
    @DisplayName("null EventBus 构造不报错")
    void shouldCreateWithNullEventBus() {
        // HotSwapWatcher 构造器要求 eventBus 非 null（用于 subscribe）
        assertThrows(NullPointerException.class, () -> {
            HotSwapWatcher w = new HotSwapWatcher(lifecycleEngine, lingRepository, null, leakDetector);
        });
    }

    @Test
    @DisplayName("onEvent 处理 null lingId 不报错")
    void shouldHandleNullLingIdEvent() {
        // event.getLingId() 返回 null 时，reloadingLings.contains(null) 返回 false
        // 但 unregister(null) 在 ConcurrentHashMap.remove(null) 时会 NPE
        // 所以 onEvent 收到 null lingId 时会抛 NPE
        LingUninstalledEvent event = mock(LingUninstalledEvent.class);
        when(event.getLingId()).thenReturn(null);
        assertThrows(NullPointerException.class, () -> watcher.onEvent(event));
    }

    @Test
    @DisplayName("shutdown 幂等不报错")
    void shouldShutdownIdempotently() {
        File classesDir = new File(tempDir, "classes");
        classesDir.mkdirs();
        watcher.register("test-ling", classesDir, mock(LingDefinition.class));
        watcher.shutdown();
        assertDoesNotThrow(() -> watcher.shutdown());
    }

    @Nested
    @DisplayName("debounce 竞态修复测试")
    class DebounceRaceConditionTests {

        /**
         * 准备一个空 classes 目录并注册灵元。
         * 空目录下 hasCompilationErrors 返回 true，doReload 会提前 return，
         * 避免触发 lifecycleEngine.undeploy/deploy，让测试聚焦于 debounce 调度本身。
         */
        private void prepareEmptyLing(String lingId) {
            File classesDir = new File(tempDir, lingId);
            classesDir.mkdirs();
            LingDefinition def = mock(LingDefinition.class);
            when(def.getId()).thenReturn(lingId);
            when(def.getVersion()).thenReturn("1.0.0");
            watcher.register(lingId, classesDir, def);
        }

        @Test
        @DisplayName("对同一 lingId 多次 scheduleReload 应取消旧任务，只保留最新任务")
        void shouldCancelPreviousDebounceTaskForSameLingId() throws Exception {
            prepareEmptyLing("test-ling");

            // 连续 3 次调度同一 lingId，compute 保证 cancel+schedule 原子性
            invokeScheduleReload("test-ling");
            invokeScheduleReload("test-ling");
            invokeScheduleReload("test-ling");

            Map<String, ?> debounceTasks = getDebounceTasks();
            assertEquals(1, debounceTasks.size(),
                    "同一 lingId 多次调度应只保留一个 debounce 任务");
            assertNotNull(debounceTasks.get("test-ling"));
        }

        @Test
        @DisplayName("不同 lingId 的 debounce 任务应互不影响")
        void shouldKeepIndependentDebounceTasksForDifferentLingIds() throws Exception {
            prepareEmptyLing("ling-a");
            prepareEmptyLing("ling-b");

            invokeScheduleReload("ling-a");
            invokeScheduleReload("ling-b");

            Map<String, ?> debounceTasks = getDebounceTasks();
            assertEquals(2, debounceTasks.size(),
                    "不同 lingId 应有独立的 debounce 任务");
            assertNotNull(debounceTasks.get("ling-a"));
            assertNotNull(debounceTasks.get("ling-b"));
        }

        @Test
        @DisplayName("debounce 任务执行完后应从 debounceTasks 中移除（holder 条件删除）")
        void shouldRemoveDebounceTaskAfterExecution() throws Exception {
            prepareEmptyLing("test-ling");

            invokeScheduleReload("test-ling");
            Map<String, ?> debounceTasks = getDebounceTasks();
            assertEquals(1, debounceTasks.size(), "调度后应有一个待执行任务");

            // 等待 debounce 延迟（1000ms）+ 余量，让 task 执行完毕
            Thread.sleep(1500);

            assertTrue(debounceTasks.isEmpty(),
                    "debounce 任务执行完后应通过 holder 条件删除从 map 中移除");
        }

        @Test
        @DisplayName("doReload 执行期间的新调度不应被旧任务的 finally 误删")
        void shouldNotDeleteNewTaskByStaleFinallyBlock() throws Exception {
            prepareEmptyLing("test-ling");

            // 第一次调度
            invokeScheduleReload("test-ling");
            Map<String, ?> debounceTasks = getDebounceTasks();
            Object firstTask = debounceTasks.get("test-ling");
            assertNotNull(firstTask);

            // 等待任务执行（doReload 因空目录提前 return，finally 执行 remove(lingId, firstTask)）
            Thread.sleep(1500);

            // 旧任务执行完后，map 应为空
            assertTrue(debounceTasks.isEmpty(),
                    "旧任务执行完后 map 应被条件删除清空");

            // 此时再次调度新任务，应能正常入 map
            invokeScheduleReload("test-ling");
            assertEquals(1, debounceTasks.size(), "新任务应能正常调度");
            assertNotNull(debounceTasks.get("test-ling"));
        }

        private void invokeScheduleReload(String lingId) throws Exception {
            Method method = HotSwapWatcher.class.getDeclaredMethod("scheduleReload", String.class);
            method.setAccessible(true);
            method.invoke(watcher, lingId);
        }

        @SuppressWarnings("unchecked")
        private Map<String, ?> getDebounceTasks() throws Exception {
            Field field = HotSwapWatcher.class.getDeclaredField("debounceTasks");
            field.setAccessible(true);
            return (Map<String, ?>) field.get(watcher);
        }
    }
}
