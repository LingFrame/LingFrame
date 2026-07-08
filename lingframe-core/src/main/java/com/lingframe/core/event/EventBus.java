package com.lingframe.core.event;

import com.lingframe.api.event.LingEvent;
import com.lingframe.api.event.LingEventListener;
import com.lingframe.core.util.NamedThreadFactory;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 框架事件总线。
 * <p>
 * 支持两种订阅模式：
 * <ul>
 *   <li><b>灵元级监听</b>：绑定 lingId，灵元卸载时自动清除（{@link #subscribe(String, Class, LingEventListener)}）</li>
 *   <li><b>全局监听</b>：框架级组件使用，不绑定灵元，生命周期由调用方管理（{@link #subscribeGlobal(Class, LingEventListener)}）</li>
 * </ul>
 * <p>
 * publish 时两类监听器都会被分发，灵元级优先、全局其次（顺序不保证业务语义，仅为确定性调试）。
 * <p>
 * 异步溢出策略：
 * <ul>
 *   <li>{@link OverflowPolicy#DISCARD}（默认）：满队列时丢弃任务并计数</li>
 *   <li>{@link OverflowPolicy#BLOCK}：满队列时阻塞调用线程直到有空间</li>
 * </ul>
 */
@Slf4j
public class EventBus {

    /**
     * 异步队列溢出策略
     */
    public enum OverflowPolicy {
        /** 满队列时丢弃任务并计数 */
        DISCARD,
        /** 满队列时阻塞调用线程直到有空间 */
        BLOCK
    }

    private static final int DEFAULT_ASYNC_THREADS = 2;
    private static final int DEFAULT_ASYNC_QUEUE_CAPACITY = 1024;

    /**
     * 监听器包装器。
     * lingId 为 null 表示全局监听器。
     */
    public static class ListenerWrapper {
        private final String lingId;
        private final LingEventListener<? extends LingEvent> listener;

        ListenerWrapper(String lingId, LingEventListener<? extends LingEvent> listener) {
            this.lingId = lingId;
            this.listener = listener;
        }

        /**
         * 归属的灵元 ID，null 表示全局监听器
         */
        public String lingId() {
            return lingId;
        }

        public LingEventListener<? extends LingEvent> listener() {
            return listener;
        }

        /**
         * 是否为全局（框架级）监听器
         */
        public boolean isGlobal() {
            return lingId == null;
        }
    }

    /**
     * eventType → 监听器列表（含灵元级和全局）
     */
    private final Map<Class<? extends LingEvent>, List<ListenerWrapper>> listeners = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor asyncDispatcher;
    private final OverflowPolicy overflowPolicy;
    private final AtomicLong droppedAsyncEvents = new AtomicLong(0);
    private final AtomicLong submittedAsyncEvents = new AtomicLong(0);

    public EventBus() {
        this(DEFAULT_ASYNC_THREADS, DEFAULT_ASYNC_QUEUE_CAPACITY, OverflowPolicy.DISCARD);
    }

    public EventBus(int asyncThreads, int asyncQueueCapacity) {
        this(asyncThreads, asyncQueueCapacity, OverflowPolicy.DISCARD);
    }

    public EventBus(OverflowPolicy overflowPolicy) {
        this(DEFAULT_ASYNC_THREADS, DEFAULT_ASYNC_QUEUE_CAPACITY, overflowPolicy);
    }

    public EventBus(int asyncThreads, int asyncQueueCapacity, OverflowPolicy overflowPolicy) {
        this.overflowPolicy = overflowPolicy != null ? overflowPolicy : OverflowPolicy.DISCARD;
        BlockingQueue<Runnable> queue = new LinkedBlockingQueue<>(Math.max(1, asyncQueueCapacity));
        this.asyncDispatcher = new ThreadPoolExecutor(
                Math.max(1, asyncThreads),
                Math.max(1, asyncThreads),
                30L,
                TimeUnit.SECONDS,
                queue,
                NamedThreadFactory.daemon("ling-eventbus-async", EventBus.class.getClassLoader()),
                new OverflowHandler(overflowPolicy, droppedAsyncEvents));
        this.asyncDispatcher.allowCoreThreadTimeOut(true);
    }

    /* ==================== 灵元级订阅 ==================== */

    /**
     * 注册灵元级监听器（绑定 lingId，灵元卸载时自动清除）
     *
     * @param lingId    灵元标识
     * @param eventType 监听的事件类型
     * @param listener  监听器实例
     */
    public <E extends LingEvent> void subscribe(String lingId, Class<E> eventType,
                                                LingEventListener<E> listener) {
        if (lingId == null) {
            throw new IllegalArgumentException("lingId must not be null, use subscribeGlobal() for framework listeners");
        }
        listeners.computeIfAbsent(eventType, k -> new CopyOnWriteArrayList<>())
                .add(new ListenerWrapper(lingId, listener));
    }

    /**
     * 取消灵元级监听器
     */
    public <E extends LingEvent> void unsubscribe(String lingId, Class<E> eventType,
                                                  LingEventListener<E> listener) {
        if (lingId == null || eventType == null || listener == null) {
            return;
        }
        List<ListenerWrapper> list = listeners.get(eventType);
        if (list == null) {
            return;
        }
        list.removeIf(w -> lingId.equals(w.lingId()) && w.listener() == listener);
        // 列表为空时清理 key，避免内存泄漏
        if (list.isEmpty()) {
            listeners.remove(eventType, list);
        }
    }

    /**
     * 卸载灵元时，清除该灵元注册的所有监听器（不影响全局监听器）
     */
    public void unsubscribeAll(String lingId) {
        log.info("Cleaning up event listeners for ling [{}]", lingId);
        for (Map.Entry<Class<? extends LingEvent>, List<ListenerWrapper>> entry : listeners.entrySet()) {
            List<ListenerWrapper> list = entry.getValue();
            list.removeIf(w -> {
                // 全局监听器不受灵元卸载影响
                if (w.isGlobal()) {
                    return false;
                }
                boolean match = lingId.equals(w.lingId());
                if (match) {
                    log.debug("Removed listener [{}] for ling [{}]",
                            w.listener().getClass().getName(), lingId);
                }
                return match;
            });
        }
    }

    /* ==================== 全局订阅（框架级组件使用） ==================== */

    /**
     * 注册全局监听器（不绑定灵元，生命周期由调用方管理）
     * <p>
     * 典型使用者：{@code RuntimeCoordinator}、审计追踪、全局指标收集等框架组件。
     *
     * @param eventType 监听的事件类型
     * @param listener  监听器实例
     */
    public <E extends LingEvent> void subscribeGlobal(Class<E> eventType,
                                                      LingEventListener<E> listener) {
        listeners.computeIfAbsent(eventType, k -> new CopyOnWriteArrayList<>())
                .add(new ListenerWrapper(null, listener));
    }

    /**
     * 取消全局监听器
     */
    public <E extends LingEvent> void unsubscribeGlobal(Class<E> eventType,
                                                        LingEventListener<E> listener) {
        if (eventType == null || listener == null) {
            return;
        }
        List<ListenerWrapper> list = listeners.get(eventType);
        if (list == null) {
            return;
        }
        list.removeIf(w -> w.isGlobal() && w.listener() == listener);
        if (list.isEmpty()) {
            listeners.remove(eventType, list);
        }
    }

    /* ==================== 事件分发 ==================== */

    /**
     * 发布事件，分发给所有匹配的监听器（灵元级 + 全局）。
     * <p>
     * 单个监听器的异常不会中断分发流程，保证所有监听器都有机会执行。
     */
    public <E extends LingEvent> void publish(E event) {
        List<ListenerWrapper> wrappers = listeners.get(event.getClass());
        if (wrappers == null || wrappers.isEmpty()) {
            return;
        }
        if (isAsyncEvent(event)) {
            dispatchAsync(event, wrappers);
            return;
        }
        dispatchSync(event, wrappers);
    }

    public long getDroppedAsyncEvents() {
        return droppedAsyncEvents.get();
    }

    public long getSubmittedAsyncEvents() {
        return submittedAsyncEvents.get();
    }

    public void shutdown() {
        // 优雅关闭：先停止接受新任务，给正在执行的异步监听器 5 秒正常完成；
        // 超时后再强制中断，避免灵元卸载时监听器处于不一致状态。
        asyncDispatcher.shutdown();
        try {
            if (!asyncDispatcher.awaitTermination(5, TimeUnit.SECONDS)) {
                log.warn("EventBus async dispatcher did not terminate within 5 seconds, forcing shutdown");
                asyncDispatcher.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            asyncDispatcher.shutdownNow();
        }
    }

    @SuppressWarnings("unchecked")
    private <E extends LingEvent> void dispatchSync(E event, List<ListenerWrapper> wrappers) {
        for (ListenerWrapper wrapper : wrappers) {
            try {
                LingEventListener<E> castListener = (LingEventListener<E>) wrapper.listener();
                castListener.onEvent(event);
            } catch (Exception e) {
                // 隔离单个监听器的异常，防止阻断分发链路和上层主逻辑
                log.error("Error dispatching event [{}] to listener [{}] (ling={}): {}",
                        event.getClass().getSimpleName(),
                        wrapper.listener().getClass().getName(),
                        wrapper.isGlobal() ? "GLOBAL" : wrapper.lingId(),
                        e.getMessage(), e);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private <E extends LingEvent> void dispatchAsync(E event, List<ListenerWrapper> wrappers) {
        for (ListenerWrapper wrapper : wrappers) {
            try {
                submittedAsyncEvents.incrementAndGet();
                Runnable task = () -> {
                    try {
                        LingEventListener<E> castListener = (LingEventListener<E>) wrapper.listener();
                        castListener.onEvent(event);
                    } catch (Exception e) {
                        log.error("Error dispatching async event [{}] to listener [{}] (ling={}): {}",
                                event.getClass().getSimpleName(),
                                wrapper.listener().getClass().getName(),
                                wrapper.isGlobal() ? "GLOBAL" : wrapper.lingId(),
                                e.getMessage(), e);
                    }
                };
                // 统一走 execute()，溢出策略由 OverflowHandler 处理
                asyncDispatcher.execute(task);
            } catch (RejectedExecutionException e) {
                droppedAsyncEvents.incrementAndGet();
                log.warn("Rejected async event [{}] for listener [{}]",
                        event.getClass().getSimpleName(), wrapper.listener().getClass().getName());
            }
        }
    }

    private boolean isAsyncEvent(LingEvent event) {
        return event instanceof AsyncLingEvent;
    }

    // ==================== 指标暴露 ====================

    public int getQueueSize() {
        return asyncDispatcher.getQueue().size();
    }

    public int getQueueRemainingCapacity() {
        return asyncDispatcher.getQueue().remainingCapacity();
    }

    public OverflowPolicy getOverflowPolicy() {
        return overflowPolicy;
    }

    /**
     * 异步队列溢出处理器。
     * <ul>
     *   <li>DISCARD：丢弃任务并计数</li>
     *   <li>BLOCK：阻塞调用线程直到队列有空间</li>
     * </ul>
     */
    private static class OverflowHandler implements RejectedExecutionHandler {
        private final OverflowPolicy policy;
        private final AtomicLong dropCounter;

        OverflowHandler(OverflowPolicy policy, AtomicLong dropCounter) {
            this.policy = policy;
            this.dropCounter = dropCounter;
        }

        @Override
        public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
            if (policy == OverflowPolicy.BLOCK) {
                try {
                    // 阻塞调用线程直到队列有空间
                    executor.getQueue().put(r);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    dropCounter.incrementAndGet();
                    log.warn("Interrupted while blocking on async event task");
                }
            } else {
                dropCounter.incrementAndGet();
                log.warn("Dropping async event task because EventBus async queue is full (queueSize={}, activeThreads={})",
                        executor.getQueue().size(), executor.getActiveCount());
            }
        }
    }
}
