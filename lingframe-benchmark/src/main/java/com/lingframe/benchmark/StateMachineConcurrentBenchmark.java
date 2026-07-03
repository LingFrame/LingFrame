package com.lingframe.benchmark;

import com.lingframe.core.fsm.StateMachine;
import com.lingframe.core.fsm.TransitionResult;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 状态机并发性能基准测试 (重构去锁版)
 * <p>
 * 【学术级性能说明：CAS 争用率 (1/N) 与无锁环形推进】
 * 在之前的单向状态跃迁压测中，重置逻辑使用了 synchronized 保护。这导致多线程并发时的性能上限被同步锁本身的
 * 内核态挂起/唤醒开销所主导。
 * 实际上，即便去除 synchronized，当 N 个线程竞争同一个单向状态跃迁（例如 READY → STOPPING）时，
 * 由于状态机的当前状态只允许一个线程修改，其余 N-1 个线程必定会以 CONFLICT 失败。从数学概率上说，
 * 并发 CAS 转换的成功率退化为 1/N，吞吐量会被碰撞率锁死在固定量级（与线程数无关）。
 * <p>
 * 为了测量底座状态机在无锁多核冲突下的真实物理自旋/跃迁上限，本次重构：
 * 1. 采用环形无锁推进（CREATED → LOADING → ... → CREATED），让多线程并发将状态机像风车一样无锁流转推进。
 * 2. 彻底移除所有 synchronized(resetLock) 限制，还原纯粹的 CAS (compareAndSet) 物理特征。
 * 3. 补充了测试非法转换判定开销的测试项。
 * <p>
 * 运行方式：
 * <pre>
 * mvn -pl lingframe-benchmark package -Pbenchmark -am -DskipTests
 * java -jar lingframe-benchmark/target/lingframe-benchmarks.jar StateMachineConcurrentBenchmark -f 3
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
        CREATED, LOADING, STARTING, READY, STOPPING, ERROR, RECOVERING
    }

    private static final Map<TestStatus, Set<TestStatus>> TRANSITIONS;

    static {
        TRANSITIONS = new EnumMap<TestStatus, Set<TestStatus>>(TestStatus.class);
        // 构建完美的无分支单向环形流转跃迁表
        TRANSITIONS.put(TestStatus.CREATED, EnumSet.of(TestStatus.LOADING));
        TRANSITIONS.put(TestStatus.LOADING, EnumSet.of(TestStatus.STARTING));
        TRANSITIONS.put(TestStatus.STARTING, EnumSet.of(TestStatus.READY));
        TRANSITIONS.put(TestStatus.READY, EnumSet.of(TestStatus.STOPPING));
        TRANSITIONS.put(TestStatus.STOPPING, EnumSet.of(TestStatus.ERROR));
        TRANSITIONS.put(TestStatus.ERROR, EnumSet.of(TestStatus.RECOVERING));
        TRANSITIONS.put(TestStatus.RECOVERING, EnumSet.of(TestStatus.CREATED));
    }

    private StateMachine<TestStatus> sharedStateMachine;

    @Setup(Level.Trial)
    public void setup() {
        sharedStateMachine = new StateMachine<TestStatus>("concurrent-bench", TestStatus.READY, TRANSITIONS);
    }

    private TestStatus getNextStatus(TestStatus curr) {
        switch (curr) {
            case CREATED: return TestStatus.LOADING;
            case LOADING: return TestStatus.STARTING;
            case STARTING: return TestStatus.READY;
            case READY: return TestStatus.STOPPING;
            case STOPPING: return TestStatus.ERROR;
            case ERROR: return TestStatus.RECOVERING;
            case RECOVERING: return TestStatus.CREATED;
            default: return TestStatus.CREATED;
        }
    }

    /**
     * 4 线程并发读当前状态
     * <p>
     * 验证在极高并发读场景下，无锁 Volatile Read 的线性吞吐极限。
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
     * 4 线程无锁环形 CAS 转换争用测试
     * <p>
     * 多线程同时读取当前状态并计算下一状态，并发执行 CAS 争抢写入。
     * 冲突的线程获得 CONFLICT，成功的线程推进状态机向前，完全无 synchronized 锁限制。
     */
    @Benchmark
    @Threads(4)
    public void concurrentCasTransition_4Threads(Blackhole bh) {
        TestStatus curr = sharedStateMachine.current();
        TestStatus next = getNextStatus(curr);
        TransitionResult<TestStatus> result = sharedStateMachine.transition(curr, next);
        bh.consume(result);
    }

    /**
     * 8 线程无锁环形 CAS 转换争用测试
     */
    @Benchmark
    @Threads(8)
    public void concurrentCasTransition_8Threads(Blackhole bh) {
        TestStatus curr = sharedStateMachine.current();
        TestStatus next = getNextStatus(curr);
        TransitionResult<TestStatus> result = sharedStateMachine.transition(curr, next);
        bh.consume(result);
    }

    /**
     * 4 线程并发读写混合
     * <p>
     * 模拟高并发只读 + 间歇性无锁 CAS 写入的混合性能。
     */
    @Benchmark
    @Threads(4)
    public void concurrentReadWrite(Blackhole bh) {
        bh.consume(sharedStateMachine.current());
        TestStatus curr = sharedStateMachine.current();
        TestStatus next = getNextStatus(curr);
        TransitionResult<TestStatus> result = sharedStateMachine.transition(curr, next);
        bh.consume(result);
    }

    /**
     * 4 线程并发非法跃迁拒绝测试
     * <p>
     * 线程直接在处于 `READY` 状态的状态机上发起非法的 `STARTING` 跃迁。
     * 根据转换表定义这属于非法流转。状态机将拒绝跃迁并返回 TransitionResult(ILLEGAL)。
     * 此测试旨在测定条件过滤、状态校验及返回结果包装对象的纯 CPU 开销（不涉及异常抛出与栈展开）。
     */
    @Benchmark
    @Threads(4)
    public void concurrentRejectedTransition(Blackhole bh) {
        // 由于 sharedStateMachine 始终处于风车推进中，我们显式传入期望值 READY 和非法值 STARTING。
        // 这将稳定触发非法转换拒绝路径，不依赖状态机当前状态。
        TransitionResult<TestStatus> result = sharedStateMachine.transition(TestStatus.READY, TestStatus.STARTING);
        bh.consume(result);
    }
}
