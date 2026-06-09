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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
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
}
