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
 *   <li>singleTransition —— 单线程状态转换基线（READY → STOPPING）</li>
 *   <li>idempotentTransition —— 幂等转换（目标态 = 当前态）</li>
 *   <li>readCurrentState —— 读取当前状态（无写入）</li>
 * </ul>
 * <p>
 * 关键设计：
 * 使用 @State(Scope.Thread) + @Setup(Level.Trial)，每个线程在 Trial 开始时
 * 创建一次 StateMachine，避免 @Setup(Level.Invocation) 每次迭代重建对象
 * 带来的分配噪声。singleTransition 通过 @TearDown(Level.Invocation) 将
 * 状态机从 STOPPING 重置回 READY，确保下次迭代可继续转换。
 * <p>
 * 并发场景见 {@link StateMachineConcurrentBenchmark}。
 *
 * <p>运行方式：
 * <pre>
 * mvn -pl lingframe-benchmark package -Pbenchmark -am -DskipTests
 * java -jar lingframe-benchmark/target/lingframe-benchmarks.jar StateMachineBenchmark
 * </pre>
 */
@BenchmarkMode({Mode.Throughput, Mode.AverageTime})
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 2)
@Fork(1)
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
     * 基线测量：单次状态转换（READY → STOPPING）的延迟
     * <p>
     * 这代表了一次灵元关停请求在状态机层面的开销。
     * 通过 @TearDown 将状态重置回 READY，确保下次迭代可继续转换。
     */
    @Benchmark
    public TransitionResult<TestStatus> singleTransition() {
        return stateMachine.transition(TestStatus.STOPPING);
    }

    /**
     * singleTransition 的重置逻辑：STOPPING → READY
     * <p>
     * 注意：此重置操作本身不纳入 singleTransition 的测量范围，
     * JMH 保证 @TearDown 在测量时间窗口之外执行。
     */
    @TearDown(Level.Invocation)
    public void resetAfterTransition() {
        if (stateMachine.current() == TestStatus.STOPPING) {
            stateMachine.transition(TestStatus.READY);
        }
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
