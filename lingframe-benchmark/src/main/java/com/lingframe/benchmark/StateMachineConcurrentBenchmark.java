package com.lingframe.benchmark;

import com.lingframe.core.fsm.StateMachine;
import com.lingframe.core.fsm.TransitionResult;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 状态机并发性能基准测试
 * <p>
 * 测量多线程下 StateMachine CAS 转换的争用表现。
 * 共享一个状态机实例，多线程同时尝试转换，验证 CAS 在高争用下的吞吐量。
 * <p>
 * 运行方式：
 * 
 * <pre>
 * mvn -pl lingframe-benchmark package -Pbenchmark -am -DskipTests
 * java -jar lingframe-benchmark/target/lingframe-benchmarks.jar StateMachineConcurrentBenchmark -f 3 -prof gc
 * </pre>
 */
@BenchmarkMode({ Mode.Throughput, Mode.AverageTime })
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 2)
@Fork(value = 1, jvmArgs = { "-Xms2g", "-Xmx2g", "-XX:+UseG1GC", "-XX:+AlwaysPreTouch", "-Dorg.slf4j.simpleLogger.defaultLogLevel=warn" })
@State(Scope.Benchmark)
public class StateMachineConcurrentBenchmark {

    enum TestStatus {
        CREATED, LOADING, STARTING, READY, STOPPING, ERROR, RECOVERING, DEAD
    }

    private static final Map<TestStatus, Set<TestStatus>> TRANSITIONS;

    static {
        TRANSITIONS = new java.util.EnumMap<TestStatus, Set<TestStatus>>(TestStatus.class);
        TRANSITIONS.put(TestStatus.CREATED, java.util.EnumSet.<TestStatus>of(TestStatus.LOADING, TestStatus.ERROR));
        TRANSITIONS.put(TestStatus.LOADING, java.util.EnumSet.<TestStatus>of(TestStatus.STARTING, TestStatus.ERROR));
        TRANSITIONS.put(TestStatus.STARTING, java.util.EnumSet.<TestStatus>of(TestStatus.READY, TestStatus.ERROR));
        TRANSITIONS.put(TestStatus.READY, java.util.EnumSet.<TestStatus>of(TestStatus.STOPPING, TestStatus.ERROR));
        TRANSITIONS.put(TestStatus.STOPPING,
                java.util.EnumSet.<TestStatus>of(TestStatus.DEAD, TestStatus.ERROR, TestStatus.READY));
        TRANSITIONS.put(TestStatus.ERROR,
                java.util.EnumSet.<TestStatus>of(TestStatus.RECOVERING, TestStatus.STOPPING, TestStatus.DEAD));
        TRANSITIONS.put(TestStatus.RECOVERING,
                java.util.EnumSet.<TestStatus>of(TestStatus.STARTING, TestStatus.ERROR, TestStatus.DEAD));
        TRANSITIONS.put(TestStatus.DEAD, java.util.EnumSet.<TestStatus>noneOf(TestStatus.class));
    }

    private StateMachine<TestStatus> sharedStateMachine;

    /** 确保重置操作只由一个线程完成的同步锁 */
    private final Object resetLock = new Object();

    @Setup(Level.Trial)
    public void setup() {
        sharedStateMachine = new StateMachine<TestStatus>("concurrent-bench", TestStatus.READY, TRANSITIONS);
    }

    /**
     * 4 线程并发读当前状态
     * <p>
     * 模拟生产中多线程同时查询灵元运行时状态的场景。
     * AtomicReference.get() 是无锁操作，预期吞吐量极高。
     */
    @Benchmark
    @Threads(4)
    public TestStatus concurrentRead_4Threads() {
        return sharedStateMachine.current();
    }

    /**
     * 8 线程并发读当前状态
     */
    @Benchmark
    @Threads(8)
    public TestStatus concurrentRead_8Threads() {
        return sharedStateMachine.current();
    }

    /**
     * 4 线程并发 CAS 转换（READY → STOPPING）
     * <p>
     * 只有一个线程能成功，其余得到 CONFLICT。
     * 模拟"多个运维操作同时触发关停"的争用场景。
     * 成功的线程通过 synchronized 保护重置逻辑，避免多线程同时重置导致状态机卡死。
     */
    @Benchmark
    @Threads(4)
    public void concurrentCasTransition(Blackhole bh) {
        TransitionResult<TestStatus> result = sharedStateMachine.transition(TestStatus.STOPPING);
        bh.consume(result);
        if (result.isSuccess()) {
            synchronized (resetLock) {
                // 双重检查：确保状态机仍在 STOPPING，避免重复重置
                if (sharedStateMachine.current() == TestStatus.STOPPING) {
                    sharedStateMachine.transition(TestStatus.STOPPING, TestStatus.READY);
                }
            }
        }
    }

    /**
     * 8 线程并发 CAS 转换
     */
    @Benchmark
    @Threads(8)
    public void concurrentCasTransition_8Threads(Blackhole bh) {
        TransitionResult<TestStatus> result = sharedStateMachine.transition(TestStatus.STOPPING);
        bh.consume(result);
        if (result.isSuccess()) {
            synchronized (resetLock) {
                if (sharedStateMachine.current() == TestStatus.STOPPING) {
                    sharedStateMachine.transition(TestStatus.STOPPING, TestStatus.READY);
                }
            }
        }
    }

    /**
     * 4 线程并发读写混合
     * <p>
     * 模拟生产中"大量路由决策读 + 偶尔状态变更"的真实场景。
     * 每次迭代先读一次状态，再尝试一次 CAS 转换。
     * 大部分线程的 CAS 会失败（CONFLICT），只有少数成功，
     * 这正是生产中"读多写少"的真实比例。
     */
    @Benchmark
    @Threads(4)
    public void concurrentReadWrite(Blackhole bh) {
        bh.consume(sharedStateMachine.current());
        TransitionResult<TestStatus> result = sharedStateMachine.transition(TestStatus.STOPPING);
        bh.consume(result);
        if (result.isSuccess()) {
            synchronized (resetLock) {
                if (sharedStateMachine.current() == TestStatus.STOPPING) {
                    sharedStateMachine.transition(TestStatus.STOPPING, TestStatus.READY);
                }
            }
        }
    }
}
