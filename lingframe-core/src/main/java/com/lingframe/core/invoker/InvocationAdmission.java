package com.lingframe.core.invoker;

import com.lingframe.api.exception.LingInvocationException;
import com.lingframe.core.ling.ActiveInvocationSnapshot;
import com.lingframe.core.ling.LingInstance;
import com.lingframe.core.ling.LingRuntime;
import com.lingframe.core.pipeline.InvocationContext;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 外层执行入口持有的实例准入凭据，覆盖治理链之后的实际业务区间。
 * <p>
 * 准入在实例内部与禁用操作串行，关闭只释放一次并断开实例引用。
 * 异步适配器必须在自己的实际完成边界关闭，不能在仅收到超时通知时提前释放。
 */
public final class InvocationAdmission implements AutoCloseable {
    private static final ScheduledExecutorService FUTURE_WATCHER = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "lingframe-admission-watcher");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicReference<LingInstance> instance;
    private final long invocationId;

    private InvocationAdmission(LingInstance instance, long invocationId) {
        this.instance = new AtomicReference<>(instance);
        this.invocationId = invocationId;
    }

    /**
     * 为已路由的真实执行取得准入；模拟和没有具体运行时的借道治理不登记在途调用。
     * @param ctx 已完成治理的调用上下文
     * @return 必须关闭的凭据，不保留调用上下文
     * @throws LingInvocationException 实例拒绝新请求或已不可用
     * @throws NullPointerException 上下文为空
     */
    public static InvocationAdmission acquire(InvocationContext ctx) {
        Objects.requireNonNull(ctx, "ctx");
        if (ctx.execution().getMode().isSimulation()) {
            return new InvocationAdmission(null, -1);
        }
        LingInstance target = ctx.routing().getTargetInstance();
        // 灵核治理链可以没有实例路由结果，其运行时只允许一个进程级实例。
        if (target == null && ctx.getRuntime() != null && !(ctx.getRuntime() instanceof LingRuntime)) {
            List<LingInstance> instances = ctx.getRuntime().getReadyInstances();
            if (instances != null && instances.size() == 1) {
                target = instances.get(0);
            }
        }
        if (target == null) {
            return new InvocationAdmission(null, -1);
        }
        ActiveInvocationSnapshot snapshot = new ActiveInvocationSnapshot(ctx.getTraceId(), ctx.getServiceFQSID(),
                ctx.getMethodName(), ctx.getCallerLingId(), ctx.getResourceId(), target.getVersion(),
                System.currentTimeMillis(), Thread.currentThread().getId(), Thread.currentThread().getName());
        long id = target.beginInvocation(snapshot);
        if (id < 0) {
            throw new LingInvocationException(ctx.getServiceFQSID(), LingInvocationException.ErrorKind.ROUTE_FAILURE,
                    "Instance is unavailable or rejects new requests");
        }
        return new InvocationAdmission(target, id);
    }

    /**
     * 将准入凭据绑定到异步结果；普通返回值立即释放，CompletionStage 在终态释放。
     * 对没有完成回调的 Future 返回一个代理，在取消或 get 观察到终态时释放。
     * @param result 业务方法返回值
     * @param admission 当前准入凭据
     * @return 原始结果或保留 Future 语义的跟踪代理
     */
    public static Object bindAsync(Object result, InvocationAdmission admission) {
        Objects.requireNonNull(admission, "admission");
        if (result instanceof CompletionStage) {
            try {
                ((CompletionStage<?>) result).whenComplete((value, failure) -> admission.close());
            } catch (RuntimeException registrationFailure) {
                admission.close();
                throw registrationFailure;
            }
            return result;
        }
        if (result instanceof Future) {
            return new TrackingFuture<>((Future<?>) result, admission);
        }
        admission.close();
        return result;
    }

    private static final class TrackingFuture<T> implements Future<T> {
        private final Future<?> delegate;
        private final InvocationAdmission admission;

        private TrackingFuture(Future<?> delegate, InvocationAdmission admission) {
            this.delegate = delegate;
            this.admission = admission;
            watchUntilDone();
        }

        private void watchUntilDone() {
            FUTURE_WATCHER.schedule(() -> {
                if (delegate.isDone()) {
                    admission.close();
                } else {
                    watchUntilDone();
                }
            }, 100, TimeUnit.MILLISECONDS);
        }

        @SuppressWarnings("unchecked")
        private T value(Object value) {
            if (delegate.isDone()) {
                admission.close();
            }
            return (T) value;
        }

        @Override public boolean cancel(boolean mayInterruptIfRunning) {
            boolean cancelled = delegate.cancel(mayInterruptIfRunning);
            if (cancelled) admission.close();
            return cancelled;
        }
        @Override public boolean isCancelled() { return delegate.isCancelled(); }
        @Override public boolean isDone() {
            boolean done = delegate.isDone();
            if (done) admission.close();
            return done;
        }
        @Override public T get() throws InterruptedException, ExecutionException {
            try { return value(delegate.get()); } catch (InterruptedException | ExecutionException e) {
                if (delegate.isDone()) admission.close();
                throw e;
            }
        }
        @Override public T get(long timeout, TimeUnit unit)
                throws InterruptedException, ExecutionException, TimeoutException {
            try { return value(delegate.get(timeout, unit)); } catch (InterruptedException | ExecutionException e) {
                if (delegate.isDone()) admission.close();
                throw e;
            }
        }
    }

    /** 释放本次在途登记；重复或并发关闭不会重复递减。 */
    @Override
    public void close() {
        LingInstance target = instance.getAndSet(null);
        if (target != null) {
            target.completeInvocation(invocationId);
        }
    }
}
