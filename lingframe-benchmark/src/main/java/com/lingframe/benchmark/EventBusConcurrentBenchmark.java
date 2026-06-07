package com.lingframe.benchmark;

import com.lingframe.api.event.LingEvent;
import com.lingframe.core.event.EventBus;
import org.openjdk.jmh.annotations.*;

import java.util.concurrent.TimeUnit;

/**
 * 事件总线并发性能基准测试
 * <p>
 * 测量多线程下 EventBus 发布的吞吐量和延迟，
 * 验证 ConcurrentHashMap + CopyOnWriteArrayList 在并发发布时的表现。
 * <p>
 * 监听器使用 volatile write 替代 AtomicLong.incrementAndGet()，
 * 消除计数器本身的 CAS 争用噪声，使结果更准确反映 EventBus 的真实并发开销。
 * <p>
 * 运行方式：
 * 
 * <pre>
 * mvn -pl lingframe-benchmark package -Pbenchmark -am -DskipTests
 * java -jar lingframe-benchmark/target/lingframe-benchmarks.jar EventBusConcurrentBenchmark -f 3 -prof gc
 * </pre>
 */
@BenchmarkMode({ Mode.Throughput, Mode.AverageTime })
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 2)
@Fork(value = 1, jvmArgs = { "-Xms2g", "-Xmx2g", "-XX:+UseG1GC", "-XX:+AlwaysPreTouch", "-Dorg.slf4j.simpleLogger.defaultLogLevel=warn" })
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

    /**
     * volatile sink 替代 AtomicLong，消除 CAS 争用噪声。
     * 每个线程写自己的 cache line 不会产生 false sharing，
     * 因为 volatile write 只保证可见性，不做 CAS 竞争。
     */
    private volatile long sink;

    @Setup(Level.Trial)
    public void setup() {
        eventBus = new EventBus();
        // 注册灵元级监听器
        for (int i = 0; i < 5; i++) {
            final String lingId = "concurrent-ling-" + i;
            eventBus.subscribe(lingId, ConcurrentEvent.class, event -> {
                sink = System.nanoTime();
            });
        }
        // 注册全局监听器 — 模拟 RuntimeCoordinator
        eventBus.subscribeGlobal(ConcurrentEvent.class, event -> {
            sink = System.nanoTime();
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
