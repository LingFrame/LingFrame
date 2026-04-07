package com.lingframe.starter.resource;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class SpringLingContainerUnloadRegressionSupport {

    private static final AtomicReference<ExecutorService> EXECUTOR = new AtomicReference<>();
    private static final AtomicInteger START_COUNT = new AtomicInteger();
    private static final AtomicInteger STOP_COUNT = new AtomicInteger();

    private SpringLingContainerUnloadRegressionSupport() {
    }

    public static void reset() {
        EXECUTOR.set(null);
        START_COUNT.set(0);
        STOP_COUNT.set(0);
    }

    public static void recordExecutor(ExecutorService executor) {
        EXECUTOR.set(executor);
    }

    public static void recordStart(String lingId) {
        START_COUNT.incrementAndGet();
    }

    public static void recordStop(String lingId) {
        STOP_COUNT.incrementAndGet();
    }

    public static ExecutorService awaitExecutor() throws InterruptedException {
        for (int i = 0; i < 20; i++) {
            ExecutorService executor = EXECUTOR.get();
            if (executor != null) {
                return executor;
            }
            TimeUnit.MILLISECONDS.sleep(25);
        }
        return null;
    }

    public static int startCount() {
        return START_COUNT.get();
    }

    public static int stopCount() {
        return STOP_COUNT.get();
    }

    public static boolean executorShutdown() {
        ExecutorService executor = EXECUTOR.get();
        return executor != null && executor.isShutdown();
    }
}
