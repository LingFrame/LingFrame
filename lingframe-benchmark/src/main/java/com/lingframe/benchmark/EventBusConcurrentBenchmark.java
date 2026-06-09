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
 * 监听器使用线程局部 volatile write 替代共享 AtomicLong.incrementAndGet()，
 * 消除跨线程写争用噪声，使结果更准确反映 EventBus 的真实并发开销。
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
@Fork(value = 1, jvmArgs = {
        "-Xms2g", "-Xmx2g", "-XX:+UseG1GC", "-XX:+AlwaysPreTouch",
        "-Dorg.slf4j.simpleLogger.defaultLogLevel=warn",
        "-XX:CompileCommand=dontinline,com/lingframe/core/pipeline/InvocationPipelineEngine::invoke",
        "-XX:CompileCommand=dontinline,com/lingframe/core/event/EventBus::publish"
})
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
     * 线程局部 sink，消除跨线程 volatile 写争用。
     * <p>
     * 若使用共享 volatile sink，8 线程 × 6 监听器 = 48 路并发写同一变量，
     * 每次写都触发 cache line invalidation，争用开销远超 EventBus 本身的并发读。
     * 使用 @State(Scope.Thread) 让每个线程写自己的 sink，彻底消除此噪声。
     * <p>
     * 注意：不能使用 System.nanoTime()，因为 JNI 系统调用开销（~25-40ns）
     * 远超 AtomicLong CAS（~5-10ns），会严重扭曲 EventBus 的真实延迟。
     * 使用常量写入 sink = 1L（~5ns），与 CAS 开销相当，不会引入额外噪声。
     */
    @State(Scope.Thread)
    public static class ThreadSink {
        public volatile long sink;
    }

    /** ThreadLocal 用于在监听器回调中获取当前线程的 ThreadSink */
    private static final ThreadLocal<ThreadSink> SINK_HOLDER = new ThreadLocal<ThreadSink>();

    @Setup(Level.Trial)
    public void setup() {
        eventBus = new EventBus();
        // 注册灵元级监听器
        for (int i = 0; i < 5; i++) {
            final String lingId = "concurrent-ling-" + i;
            eventBus.subscribe(lingId, ConcurrentEvent.class, event -> {
                // EventBus.publish() 是同步调用，发布线程即监听器执行线程，
                // 通过 ThreadLocal 获取当前线程的 sink 实例
                ThreadSink ts = SINK_HOLDER.get();
                if (ts != null) {
                    ts.sink = 1L;
                }
            });
        }
        // 注册全局监听器 — 模拟 RuntimeCoordinator
        eventBus.subscribeGlobal(ConcurrentEvent.class, event -> {
            ThreadSink ts = SINK_HOLDER.get();
            if (ts != null) {
                ts.sink = 1L;
            }
        });
    }

    /**
     * 1 线程发布基线
     */
    @Benchmark
    @Threads(1)
    public void publish_1Thread(ThreadSink ts) {
        SINK_HOLDER.set(ts);
        try {
            eventBus.publish(new ConcurrentEvent("publisher"));
        } finally {
            SINK_HOLDER.remove();
        }
    }

    /**
     * 4 线程并发发布
     */
    @Benchmark
    @Threads(4)
    public void publish_4Threads(ThreadSink ts) {
        SINK_HOLDER.set(ts);
        try {
            eventBus.publish(new ConcurrentEvent("publisher"));
        } finally {
            SINK_HOLDER.remove();
        }
    }

    /**
     * 8 线程并发发布
     */
    @Benchmark
    @Threads(8)
    public void publish_8Threads(ThreadSink ts) {
        SINK_HOLDER.set(ts);
        try {
            eventBus.publish(new ConcurrentEvent("publisher"));
        } finally {
            SINK_HOLDER.remove();
        }
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        for (int i = 0; i < 5; i++) {
            eventBus.unsubscribeAll("concurrent-ling-" + i);
        }
        eventBus.shutdown();
    }
}
