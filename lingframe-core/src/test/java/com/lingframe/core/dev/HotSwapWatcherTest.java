package com.lingframe.core.dev;

import com.lingframe.core.event.EventBus;
import com.lingframe.core.ling.LingLifecycleEngine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

@ExtendWith(MockitoExtension.class)
class HotSwapWatcherTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("重复注册同一灵元时不应累积 WatchKey")
    void registerShouldCleanupPreviousKeys() throws Exception {
        EventBus eventBus = mock(EventBus.class);
        LingLifecycleEngine lifecycleEngine = mock(LingLifecycleEngine.class);
        HotSwapWatcher watcher = new HotSwapWatcher(lifecycleEngine, eventBus);

        Path root = tempDir.resolve("classes");
        Files.createDirectories(root.resolve("sub"));

        watcher.register("ling-a", root.toFile());
        int size1 = getKeyLingMapSize(watcher);
        assertTrue(size1 > 0, "expected watch keys to be registered");

        watcher.register("ling-a", root.toFile());
        int size2 = getKeyLingMapSize(watcher);

        watcher.shutdown();

        assertEquals(size1, size2, "watch key count should not grow on re-register");
    }

    @SuppressWarnings("unchecked")
    private int getKeyLingMapSize(HotSwapWatcher watcher) throws Exception {
        Field field = HotSwapWatcher.class.getDeclaredField("keyLingMap");
        field.setAccessible(true);
        Map<Object, Object> map = (Map<Object, Object>) field.get(watcher);
        return map.size();
    }
}
