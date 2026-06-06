package com.lingframe.benchmark;

import com.lingframe.api.event.LingEvent;
import com.lingframe.api.event.LingEventListener;
import com.lingframe.core.event.EventBus;
import org.openjdk.jmh.annotations.*;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 事件总线性能基准测试
 * <p>
 * 测量 EventBus 的发布/订阅吞吐量，
 * 为 SSE 事件流和内部事件驱动架构提供性能基线。
 * <p>
 * 测试场景覆盖：
 * <ul>
 *   <li>publishToLingListeners —— 发布到灵元级监听器（5 个）</li>
 *   <li>publishToGlobalListener —— 发布到全局监听器（1 个）</li>
 *   <li>publishNoListeners —— 发布到无匹配监听器的事件类型</li>
 * </ul>
 * <p>
 * 关键设计：灵元级和全局级使用不同的事件类型，确保 publish 只触发目标监听器，
 * 而非同时触发两类。EventBus.publish() 按 eventType 分发，不按 sourceLingId 过滤。
 * <p>
 * 并发场景见 {@link EventBusConcurrentBenchmark}。
 *
 * <p>运行方式：
 * <pre>
 * mvn -pl lingframe-benchmark package -Pbenchmark -am -DskipTests
 * java -jar lingframe-benchmark/target/lingframe-benchmarks.jar EventBusBenchmark
 * </pre>
 */
@BenchmarkMode({Mode.Throughput, Mode.AverageTime})
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 2)
@Fork(1)
@State(Scope.Benchmark)
public class EventBusBenchmark {

    /** 灵元级事件类型 — 只有灵元级监听器订阅 */
    public static class BenchLingEvent implements LingEvent {
        private final String source;

        public BenchLingEvent(String source) {
            this.source = source;
        }

        public String sourceLingId() {
            return source;
        }
    }

    /** 全局事件类型 — 只有全局监听器订阅 */
    public static class BenchGlobalEvent implements LingEvent {
        private final String source;

        public BenchGlobalEvent(String source) {
            this.source = source;
        }

        public String sourceLingId() {
            return source;
        }
    }

    /** 无订阅者事件类型 — 测量空分发开销 */
    public static class BenchUnsubscribedEvent implements LingEvent {
    }

    private EventBus eventBus;
    private final AtomicLong lingReceivedCount = new AtomicLong(0);
    private final AtomicLong globalReceivedCount = new AtomicLong(0);

    @Setup(Level.Trial)
    public void setup() {
        eventBus = new EventBus();

        // 灵元级监听器只订阅 BenchLingEvent
        for (int i = 0; i < 5; i++) {
            final String lingId = "bench-ling-" + i;
            eventBus.subscribe(lingId, BenchLingEvent.class, event -> {
                lingReceivedCount.incrementAndGet();
            });
        }

        // 全局监听器只订阅 BenchGlobalEvent
        eventBus.subscribeGlobal(BenchGlobalEvent.class, event -> {
            globalReceivedCount.incrementAndGet();
        });
    }

    /**
     * 测量发布到 5 个灵元级监听器的延迟
     * <p>
     * LingEvent 只被灵元级监听器订阅，不触发全局监听器。
     */
    @Benchmark
    public void publishToLingListeners() {
        eventBus.publish(new BenchLingEvent("publisher"));
    }

    /**
     * 测量发布到 1 个全局监听器的延迟
     * <p>
     * GlobalEvent 只被全局监听器订阅，不触发灵元级监听器。
     */
    @Benchmark
    public void publishToGlobalListener() {
        eventBus.publish(new BenchGlobalEvent("publisher"));
    }

    /**
     * 测量发布不匹配任何监听器的事件（空分发）
     * <p>
     * UnsubscribedEvent 无任何订阅者，publish 只做一次 ConcurrentHashMap.get() + 空列表判断。
     */
    @Benchmark
    public void publishNoListeners() {
        eventBus.publish(new BenchUnsubscribedEvent());
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        for (int i = 0; i < 5; i++) {
            eventBus.unsubscribeAll("bench-ling-" + i);
        }
        eventBus.shutdown();
    }
}
