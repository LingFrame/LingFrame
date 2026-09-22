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
 * 双层状态机性能基准测试
 * <p>
 * 测量 StateMachine CAS 转换的吞吐量和延迟特征，
 * 为状态机在高并发场景下的性能提供基线数据。
 * <p>
 * 测试场景覆盖：
 * <ul>
 *   <li>singleRoundTrip —— 单线程往返转换（READY → STOPPING → READY），报告值为两次 CAS 之和</li>
 *   <li>idempotentTransition —— 幂等转换（目标态 = 当前态）</li>
 *   <li>readCurrentState —— 读取当前状态（无写入）</li>
 * </ul>
 * <p>
 * 关键设计：
 * 使用 round-trip 测量法替代旧版的 @TearDown(Level.Invocation) 重置方案。
 * Level.Invocation 对纳秒级操作有显著测量干扰（JMH 官方警告），
 * round-trip 在 benchmark 方法内完成状态往返，消除框架开销噪声。
 * 单次 CAS 转换开销 ≈ singleRoundTrip / 2。
 * <p>
 * 并发场景见 {@link StateMachineConcurrentBenchmark}。
 *
 * <p>运行方式：
 * <pre>
 * mvn -pl lingframe-benchmark package -Pbenchmark -am -DskipTests
 * java -jar lingframe-benchmark/target/lingframe-benchmarks.jar StateMachineBenchmark -f 3 -prof gc
 * </pre>
 */
@BenchmarkMode({Mode.Throughput, Mode.AverageTime})
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 2)
@Fork(value = 1, jvmArgs = {"-Xms2g", "-Xmx2g", "-XX:+UseG1GC", "-XX:+AlwaysPreTouch", "-Dorg.slf4j.simpleLogger.defaultLogLevel=warn"})
@State(Scope.Thread)
public class StateMachineBenchmark {

    /**
     * 测试用状态枚举 — 模拟 InstanceStatus 的 8 态模型
     */
    enum TestStatus {
        CREATED, LOADING, STARTING, READY, STOPPING, ERROR, RECOVERING, DEAD
    }

    private StateMachine<TestStatus> stateMachine;
    static final Map<TestStatus, Set<TestStatus>> TRANSITIONS;

    static {
        TRANSITIONS = new EnumMap<TestStatus, Set<TestStatus>>(TestStatus.class);
        TRANSITIONS.put(TestStatus.CREATED, EnumSet.<TestStatus>of(TestStatus.LOADING, TestStatus.ERROR));
        TRANSITIONS.put(TestStatus.LOADING, EnumSet.<TestStatus>of(TestStatus.STARTING, TestStatus.ERROR));
        TRANSITIONS.put(TestStatus.STARTING, EnumSet.<TestStatus>of(TestStatus.READY, TestStatus.ERROR));
        TRANSITIONS.put(TestStatus.READY, EnumSet.<TestStatus>of(TestStatus.STOPPING, TestStatus.ERROR));
        TRANSITIONS.put(TestStatus.STOPPING, EnumSet.<TestStatus>of(TestStatus.DEAD, TestStatus.ERROR, TestStatus.READY));
        TRANSITIONS.put(TestStatus.ERROR, EnumSet.<TestStatus>of(TestStatus.RECOVERING, TestStatus.STOPPING, TestStatus.DEAD));
        TRANSITIONS.put(TestStatus.RECOVERING, EnumSet.<TestStatus>of(TestStatus.STARTING, TestStatus.ERROR, TestStatus.DEAD));
        TRANSITIONS.put(TestStatus.DEAD, EnumSet.<TestStatus>noneOf(TestStatus.class));
    }

    @Setup(Level.Trial)
    public void setup() {
        stateMachine = new StateMachine<TestStatus>("bench", TestStatus.READY, TRANSITIONS);
    }

    /**
     * 基线测量：往返状态转换（READY → STOPPING → READY）的延迟
     * <p>
     * 使用 round-trip 法替代 @TearDown(Level.Invocation)：
     * 在 benchmark 方法内完成正向转换和反向重置，消除 Level.Invocation 的框架噪声。
     * 单次 CAS 转换开销 ≈ 报告值 / 2。
     */
    @Benchmark
    public void singleRoundTrip(Blackhole bh) {
        bh.consume(stateMachine.transition(TestStatus.STOPPING));
        bh.consume(stateMachine.transition(TestStatus.READY));
    }

    /**
     * 基线测量：幂等转换（目标态 = 当前态）
     * <p>
     * 验证幂等路径不触发 CAS 写入的性能特征。
     * 无需重置，因为状态未变。
     */
    @Benchmark
    public TransitionResult<TestStatus> idempotentTransition() {
        return stateMachine.transition(TestStatus.READY);
    }

    /**
     * 基线测量：读取当前状态（无写入）
     * <p>
     * 代表路由决策时读取 RuntimeStatus 的开销。
     */
    @Benchmark
    public TestStatus readCurrentState() {
        return stateMachine.current();
    }
}
