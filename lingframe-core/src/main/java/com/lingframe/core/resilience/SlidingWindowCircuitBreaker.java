package com.lingframe.core.resilience;

import com.lingframe.core.event.EventBus;
import com.lingframe.core.event.monitor.MonitoringEvents;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 滑动窗口熔断器 (简化版)
 * <p>
 * 使用固定大小的 RingBuffer 统计最近 N 次请求的失败率。
 * 避免引入 Resilience4j 依赖。
 */
@Slf4j
public class SlidingWindowCircuitBreaker implements CircuitBreaker {

    private final String name;
    private final int failureRateThreshold; // 失败率阈值 (0-100)
    private final int slowCallRateThreshold; // 慢调用阈值 (0-100)
    private final long slowCallDurationThresholdMs; // 慢调用时间阈值
    private final int slidingWindowSize; // 窗口大小
    private final int minimumNumberOfCalls; // 最小调用数
    private final long waitDurationInOpenStateMs; // 熔断后等待时间

    // 状态
    private final AtomicReference<State> state = new AtomicReference<>(State.CLOSED);
    private final AtomicLong stateTransitionTime = new AtomicLong(System.currentTimeMillis());

    // 统计 (环形缓冲区)
    private final boolean[] failureWindow;
    private final boolean[] slowWindow;
    private final AtomicInteger currentIndex = new AtomicInteger(0);
    private final AtomicInteger totalCallsInWindow = new AtomicInteger(0);
    private final AtomicInteger failureCount = new AtomicInteger(0);
    private final AtomicInteger slowCount = new AtomicInteger(0);

    // Half-Open 状态下的试探计数
    private final AtomicInteger permittedNumberOfCallsInHalfOpenState = new AtomicInteger(10);
    private final AtomicInteger successfulCallsInHalfOpenState = new AtomicInteger(0);

    private final EventBus eventBus;

    public SlidingWindowCircuitBreaker(String name, int failureRateThreshold, int slowCallRateThreshold,
                                       long slowCallDurationThresholdMs, int slidingWindowSize, int minimumNumberOfCalls,
                                       long waitDurationInOpenStateMs) {
        this(name, failureRateThreshold, slowCallRateThreshold, slowCallDurationThresholdMs, slidingWindowSize,
                minimumNumberOfCalls, waitDurationInOpenStateMs, null);
    }

    public SlidingWindowCircuitBreaker(String name, int failureRateThreshold, int slowCallRateThreshold,
                                       long slowCallDurationThresholdMs, int slidingWindowSize, int minimumNumberOfCalls,
                                       long waitDurationInOpenStateMs, EventBus eventBus) {
        this.name = name;
        this.failureRateThreshold = failureRateThreshold;
        this.slowCallRateThreshold = slowCallRateThreshold;
        this.slowCallDurationThresholdMs = slowCallDurationThresholdMs;
        this.slidingWindowSize = slidingWindowSize;
        this.minimumNumberOfCalls = minimumNumberOfCalls;
        this.waitDurationInOpenStateMs = waitDurationInOpenStateMs;

        this.failureWindow = new boolean[slidingWindowSize];
        this.slowWindow = new boolean[slidingWindowSize];
        this.eventBus = eventBus;
    }

    @Override
    public boolean tryAcquirePermission() {
        State currentState = state.get();

        if (currentState == State.FORCED_OPEN)
            return false;
        if (currentState == State.DISABLED)
            return true;

        if (currentState == State.OPEN) {
            long now = System.currentTimeMillis();
            if (now - stateTransitionTime.get() > waitDurationInOpenStateMs) {
                if (state.compareAndSet(State.OPEN, State.HALF_OPEN)) {
                    stateTransitionTime.set(now);
                    successfulCallsInHalfOpenState.set(0);
                    log.info("[Breaker:{}] State changed: OPEN -> HALF_OPEN (Trial starts)", name);
                    return true;
                }
            }
            return false;
        }

        if (currentState == State.HALF_OPEN) {
            // 这里可以做简单并发控制，暂略
            return true;
        }

        return true;
    }

    @Override
    public synchronized void onSuccess(long duration, TimeUnit durationUnit) {
        record(duration, durationUnit, false);
    }

    @Override
    public synchronized void onError(long duration, TimeUnit durationUnit, Throwable throwable) {
        record(duration, durationUnit, true);
    }

    private void record(long duration, TimeUnit durationUnit, boolean isError) {
        State currentState = state.get();
        if (currentState == State.OPEN || currentState == State.FORCED_OPEN || currentState == State.DISABLED) {
            return;
        }

        long durationMs = durationUnit.toMillis(duration);
        boolean isSlow = durationMs >= slowCallDurationThresholdMs;

        if (currentState == State.HALF_OPEN) {
            if (isError) {
                transitionToOpen();
            } else {
                int successes = successfulCallsInHalfOpenState.incrementAndGet();
                if (successes >= permittedNumberOfCallsInHalfOpenState.get()) {
                    transitionToClosed();
                }
            }
            return;
        }

        // CLOSED State: Update sliding window
        int idx = currentIndex.getAndIncrement() % slidingWindowSize;

        // Remove old values
        if (failureWindow[idx])
            failureCount.decrementAndGet();
        if (slowWindow[idx])
            slowCount.decrementAndGet();

        // Add new values
        failureWindow[idx] = isError;
        if (isError)
            failureCount.incrementAndGet();

        slowWindow[idx] = isSlow;
        if (isSlow)
            slowCount.incrementAndGet();

        if (totalCallsInWindow.get() < slidingWindowSize) {
            totalCallsInWindow.incrementAndGet();
        }

        checkThresholds();
    }

    private void checkThresholds() {
        if (totalCallsInWindow.get() < minimumNumberOfCalls) {
            return;
        }

        double failRate = (double) failureCount.get() / totalCallsInWindow.get() * 100;
        double slowRate = (double) slowCount.get() / totalCallsInWindow.get() * 100;

        if (failRate >= failureRateThreshold) {
            log.warn("[Breaker:{}] Failure rate {}% exceeds threshold {}%. OPENING.", name,
                    String.format("%.2f", failRate), failureRateThreshold);
            transitionToOpen();
        } else if (slowRate >= slowCallRateThreshold) {
            log.warn("[Breaker:{}] Slow call rate {}% exceeds threshold {}%. OPENING.", name,
                    String.format("%.2f", slowRate), slowCallRateThreshold);
            transitionToOpen();
        }
    }

    private void transitionToOpen() {
        State oldState = state.get();
        if (state.compareAndSet(State.CLOSED, State.OPEN) || state.compareAndSet(State.HALF_OPEN, State.OPEN)) {
            stateTransitionTime.set(System.currentTimeMillis());
            publishStateEvent(oldState, State.OPEN);
        }
    }

    private void publishStateEvent(State oldState, State newState) {
        if (eventBus != null) {
            try {
                eventBus.publish(
                        new MonitoringEvents.CircuitBreakerStateEvent(name, oldState.name(), newState.name(), 0.0));
            } catch (Exception e) {
                // ignore
            }
        }
    }

    private void transitionToClosed() {
        if (state.compareAndSet(State.HALF_OPEN, State.CLOSED)) {
            log.info("[Breaker:{}] State changed: HALF_OPEN -> CLOSED (Recovered)", name);
            publishStateEvent(State.HALF_OPEN, State.CLOSED);
            // Reset window
            currentIndex.set(0);
            totalCallsInWindow.set(0);
            failureCount.set(0);
            slowCount.set(0);
            for (int i = 0; i < slidingWindowSize; i++) {
                failureWindow[i] = false;
                slowWindow[i] = false;
            }
        }
    }

    @Override
    public State getState() {
        return state.get();
    }
}
