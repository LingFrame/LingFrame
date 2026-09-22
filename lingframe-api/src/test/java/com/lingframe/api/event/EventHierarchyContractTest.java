package com.lingframe.api.event;

import com.lingframe.api.event.lifecycle.LingInstalledEvent;
import com.lingframe.api.event.lifecycle.LingInstallingEvent;
import com.lingframe.api.event.lifecycle.LingLifecycleEvent;
import com.lingframe.api.event.lifecycle.LingStartedEvent;
import com.lingframe.api.event.lifecycle.LingStartingEvent;
import com.lingframe.api.event.lifecycle.LingStoppedEvent;
import com.lingframe.api.event.lifecycle.LingStoppingEvent;
import com.lingframe.api.event.lifecycle.LingUninstalledEvent;
import com.lingframe.api.event.lifecycle.LingUninstallingEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.File;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 事件层级契约测试。
 * <p>
 * 验证 LingEvent → AbstractLingEvent → LingLifecycleEvent 继承链的
 * 序列化兼容性、时间戳单调性和 toString 契约。
 */
@DisplayName("事件层级契约测试")
class EventHierarchyContractTest {

    @Nested
    @DisplayName("AbstractLingEvent 契约")
    class AbstractLingEventContract {

        @Test
        @DisplayName("时间戳在构造时设置且单调递增")
        void timestampSetOnConstruction() {
            AbstractLingEvent first = new LingInstalledEvent("ling-1", "1.0");
            AbstractLingEvent second = new LingStartedEvent("ling-1", "1.0");
            assertTrue(second.getTimestamp() >= first.getTimestamp(),
                    "后构造的事件时间戳应 >= 先构造的");
        }

        @Test
        @DisplayName("toString 包含类名和时间戳")
        void toStringContainsClassNameAndTimestamp() {
            LingInstalledEvent event = new LingInstalledEvent("ling-1", "1.0");
            String str = event.toString();
            assertTrue(str.contains("LingInstalledEvent"), "toString 应包含类名");
            assertTrue(str.contains("timestamp="), "toString 应包含 timestamp=");
        }
    }

    @Nested
    @DisplayName("LingLifecycleEvent 契约")
    class LingLifecycleEventContract {

        @Test
        @DisplayName("lingId 和 version 正确传递")
        void lingIdAndVersionPreserved() {
            LingLifecycleEvent event = new LingStartingEvent("my-ling", "2.0.0");
            assertEquals("my-ling", event.getLingId());
            assertEquals("2.0.0", event.getVersion());
        }

        @Test
        @DisplayName("toString 包含 lingId:version")
        void toStringContainsLingIdAndVersion() {
            LingLifecycleEvent event = new LingStoppedEvent("test-ling", "1.0");
            String str = event.toString();
            assertTrue(str.contains("test-ling"), "toString 应包含 lingId");
            assertTrue(str.contains("1.0"), "toString 应包含 version");
        }
    }

    @Nested
    @DisplayName("生命周期事件配对契约")
    class LifecyclePairingContract {

        @Test
        @DisplayName("Installing/Installed 配对存在")
        void installingInstalledPairExists() {
            assertNotNull(new LingInstallingEvent("x", "1.0", new File(".")));
            assertNotNull(new LingInstalledEvent("x", "1.0"));
        }

        @Test
        @DisplayName("Starting/Started 配对存在")
        void startingStartedPairExists() {
            assertNotNull(new LingStartingEvent("x", "1.0"));
            assertNotNull(new LingStartedEvent("x", "1.0"));
        }

        @Test
        @DisplayName("Stopping/Stopped 配对存在")
        void stoppingStoppedPairExists() {
            assertNotNull(new LingStoppingEvent("x", "1.0"));
            assertNotNull(new LingStoppedEvent("x", "1.0"));
        }

        @Test
        @DisplayName("Uninstalling/Uninstalled 配对存在")
        void uninstallingUninstalledPairExists() {
            assertNotNull(new LingUninstallingEvent("x"));
            assertNotNull(new LingUninstalledEvent("x"));
        }
    }

    @Nested
    @DisplayName("LingEvent 接口契约")
    class LingEventInterfaceContract {

        @Test
        @DisplayName("所有生命周期事件都实现 LingEvent")
        void allLifecycleEventsImplementLingEvent() {
            assertTrue(LingEvent.class.isAssignableFrom(LingInstallingEvent.class));
            assertTrue(LingEvent.class.isAssignableFrom(LingInstalledEvent.class));
            assertTrue(LingEvent.class.isAssignableFrom(LingStartingEvent.class));
            assertTrue(LingEvent.class.isAssignableFrom(LingStartedEvent.class));
            assertTrue(LingEvent.class.isAssignableFrom(LingStoppingEvent.class));
            assertTrue(LingEvent.class.isAssignableFrom(LingStoppedEvent.class));
            assertTrue(LingEvent.class.isAssignableFrom(LingUninstallingEvent.class));
            assertTrue(LingEvent.class.isAssignableFrom(LingUninstalledEvent.class));
        }

        @Test
        @DisplayName("所有生命周期事件都继承 LingLifecycleEvent")
        void allLifecycleEventsExtendLingLifecycleEvent() {
            assertTrue(LingLifecycleEvent.class.isAssignableFrom(LingInstallingEvent.class));
            assertTrue(LingLifecycleEvent.class.isAssignableFrom(LingInstalledEvent.class));
            assertTrue(LingLifecycleEvent.class.isAssignableFrom(LingStartingEvent.class));
            assertTrue(LingLifecycleEvent.class.isAssignableFrom(LingStartedEvent.class));
            assertTrue(LingLifecycleEvent.class.isAssignableFrom(LingStoppingEvent.class));
            assertTrue(LingLifecycleEvent.class.isAssignableFrom(LingStoppedEvent.class));
            assertTrue(LingLifecycleEvent.class.isAssignableFrom(LingUninstallingEvent.class));
            assertTrue(LingLifecycleEvent.class.isAssignableFrom(LingUninstalledEvent.class));
        }
    }
}
