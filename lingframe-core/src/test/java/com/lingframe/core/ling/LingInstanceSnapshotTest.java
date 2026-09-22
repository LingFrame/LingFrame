package com.lingframe.core.ling;

import com.lingframe.core.fsm.InstanceStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 实例代次状态快照测试。 */
@DisplayName("实例代次状态快照")
class LingInstanceSnapshotTest {
    @Test
    @DisplayName("实例本身能生成与代次绑定的运行快照")
    void instanceCreatesSnapshot() {
        com.lingframe.api.config.LingDefinition definition = new com.lingframe.api.config.LingDefinition();
        definition.setId("a");
        definition.setVersion("v1");
        com.lingframe.core.spi.LingContainer container = mock(com.lingframe.core.spi.LingContainer.class);
        when(container.getClassLoader()).thenReturn(getClass().getClassLoader());
        LingInstance instance = new LingInstance(container, definition, null);
        LingInstanceSnapshot snapshot = instance.snapshot(false);
        assertEquals(instance.getInstanceId(), snapshot.getInstanceId());
        assertEquals("a", snapshot.getLingId());
        assertEquals("v1", snapshot.getVersion());
        assertEquals(0, snapshot.getActiveRequestCount());
        assertFalse(snapshot.isDefaultInstance());
    }

    @Test
    @DisplayName("快照保留实例身份、生命周期和准入事实")
    void exposesRuntimeFacts() {
        LingInstanceSnapshot snapshot = new LingInstanceSnapshot("a@v1#1", "a", "v1",
                InstanceStatus.READY, true, true, 3);
        assertEquals("a@v1#1", snapshot.getInstanceId());
        assertEquals("a", snapshot.getLingId());
        assertEquals("v1", snapshot.getVersion());
        assertTrue(snapshot.isReady());
        assertTrue(snapshot.isDefaultInstance());
        assertTrue(snapshot.isAdmissionDisabled());
        assertEquals(3, snapshot.getActiveRequestCount());
        assertFalse(snapshot.isDraining());
    }

    @Test
    @DisplayName("快照拒绝空身份和负在途数量")
    void rejectsInvalidFacts() {
        assertThrows(NullPointerException.class,
                () -> new LingInstanceSnapshot(null, "a", "v1", InstanceStatus.READY, false, false, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new LingInstanceSnapshot("a@v1#1", "a", "v1", InstanceStatus.READY, false, false, -1));
    }
}
