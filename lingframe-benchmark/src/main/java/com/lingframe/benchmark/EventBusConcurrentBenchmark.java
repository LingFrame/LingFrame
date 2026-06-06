package com.lingframe.benchmark;

import com.lingframe.api.event.LingEvent;
import com.lingframe.api.event.LingEventListener;
import com.lingframe.core.event.EventBus;
import org.openjdk.jmh.annotations.*;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 事件总线并发性能基准测试
 * <p>
 * 测量多线程下 EventBus 发布的吞吐量和延迟，
 * 验证 ConcurrentHashMap + CopyOnWriteArrayList 在并发发布时的表现。
 * <p>
 * 运行方式：
 * <pre>
 * mvn -pl lingframe-benchmark package -Pbenchmark -am -DskipTests
 * java -jar lingframe-benchmark/target/lingframe-benchmarks.jar EventBusConcurrentBenchmark
 * </pre>
 */
@BenchmarkMode({Mode.Throughput, Mode.AverageTime})
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 2)
@Fork(1)
@State(Scope.Benchmark)
public class EventBusConcurrentBenchmark {

    public static class ConcurrentEvent implements LingEvent {
        private final String source;

        public ConcurrentEvent(String source) {
            this.source = source;
        }

        public String sourceLingId() {
            return source;
        }
    }

    private EventBus eventBus;
    private final AtomicLong receivedCount = new AtomicLong(0);

    @Setup(Level.Trial)
    public void setup() {
        eventBus = new EventBus();
        // 注册灵元级监听器
        for (int i = 0; i < 5; i++) {
            final String lingId = "concurrent-ling-" + i;
            eventBus.subscribe(lingId, ConcurrentEvent.class, event -> {
                receivedCount.incrementAndGet();
            });
        }
        // 注册全局监听器 — 模拟 RuntimeCoordinator
        eventBus.subscribeGlobal(ConcurrentEvent.class, event -> {
            receivedCount.incrementAndGet();
        });
    }

    /**
     * 1 线程发布基线
     */
    @Benchmark
    @Threads(1)
    public void publish_1Thread() {
        eventBus.publish(new ConcurrentEvent("publisher"));
    }

    /**
     * 4 线程并发发布
     */
    @Benchmark
    @Threads(4)
    public void publish_4Threads() {
        eventBus.publish(new ConcurrentEvent("publisher"));
    }

    /**
     * 8 线程并发发布
     */
    @Benchmark
    @Threads(8)
    public void publish_8Threads() {
        eventBus.publish(new ConcurrentEvent("publisher"));
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        for (int i = 0; i < 5; i++) {
            eventBus.unsubscribeAll("concurrent-ling-" + i);
        }
        eventBus.shutdown();
    }
}
