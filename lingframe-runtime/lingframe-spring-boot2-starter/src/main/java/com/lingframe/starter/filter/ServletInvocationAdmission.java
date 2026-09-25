package com.lingframe.starter.filter;

import com.lingframe.core.invoker.InvocationAdmission;
import javax.servlet.AsyncContext;
import javax.servlet.AsyncEvent;
import javax.servlet.AsyncListener;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;
import javax.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * 将实例准入凭据绑定到 Servlet 执行区间及异步完成事实。
 * 错误或超时通知不代表业务已结束；只有初始调用已返回且异步完成后才能释放。
 */
final class ServletInvocationAdmission implements AsyncListener, AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(ServletInvocationAdmission.class);
    private final InvocationAdmission admission;
    private final HttpServletResponse response;
    private final Consumer<Throwable> outcomeReporter;
    private boolean returned;
    private boolean asynchronous;
    private boolean completed;
    private boolean outcomeReported;
    private Throwable completionFailure;

    ServletInvocationAdmission(InvocationAdmission admission) {
        this(admission, null, null);
    }

    ServletInvocationAdmission(InvocationAdmission admission, HttpServletResponse response,
            Consumer<Throwable> outcomeReporter) {
        this.admission = admission;
        this.response = response;
        this.outcomeReporter = outcomeReporter;
    }

    /** 在 startAsync 返回给业务之前登记监听器，避免完成通知早于注册。 */
    HttpServletRequest wrap(HttpServletRequest request, HttpServletResponse response) {
        if (!request.isAsyncSupported()) {
            return request;
        }
        if (request.isAsyncStarted()) {
            track(request.getAsyncContext());
        }
        return new HttpServletRequestWrapper(request) {
            @Override
            public AsyncContext startAsync() {
                return track(super.startAsync(this, response));
            }

            @Override
            public AsyncContext startAsync(ServletRequest servletRequest, ServletResponse servletResponse) {
                return track(super.startAsync(servletRequest, servletResponse));
            }
        };
    }

    private AsyncContext track(AsyncContext context) {
        synchronized (this) {
            asynchronous = true;
        }
        try {
            context.addListener(this);
        } catch (RuntimeException error) {
            // 无法证明完成时保留在途登记，不能把监听失败伪装成执行结束。
            log.error("Cannot track async completion; invocation admission remains active", error);
            throw error;
        }
        return context;
    }

    @Override
    public synchronized void close() {
        complete(null);
    }

    synchronized void complete(Throwable failure) {
        if (failure != null) {
            completionFailure = failure;
        }
        returned = true;
        releaseIfComplete();
        reportOutcomeIfComplete();
    }

    @Override
    public synchronized void onComplete(AsyncEvent event) {
        completed = true;
        releaseIfComplete();
        reportOutcomeIfComplete();
    }

    @Override
    public synchronized void onTimeout(AsyncEvent event) {
        // 超时只是通知，异步业务可能仍在运行；等 onComplete 再结算。
        if (completionFailure == null) {
            completionFailure = new TimeoutException("Servlet invocation timed out");
        }
    }

    @Override
    public synchronized void onError(AsyncEvent event) {
        // 错误不等同于完成，等待容器确认请求结束。
        if (event.getThrowable() != null) {
            completionFailure = event.getThrowable();
        }
    }

    @Override
    public void onStartAsync(AsyncEvent event) {
        synchronized (this) {
            completed = false;
            completionFailure = null;
        }
        track(event.getAsyncContext());
    }

    private void releaseIfComplete() {
        if (returned && (!asynchronous || completed)) {
            admission.close();
        }
    }

    private void reportOutcomeIfComplete() {
        if (outcomeReporter == null || outcomeReported
                || !returned || (asynchronous && !completed)) {
            return;
        }
        outcomeReported = true;
        Throwable failure = completionFailure;
        if (failure == null && response != null) {
            try {
                if (response.getStatus() >= HttpServletResponse.SC_INTERNAL_SERVER_ERROR) {
                    failure = new IllegalStateException("HTTP response status " + response.getStatus());
                }
            } catch (RuntimeException statusFailure) {
                failure = statusFailure;
            }
        }
        try {
            outcomeReporter.accept(failure);
        } catch (RuntimeException reportFailure) {
            log.debug("Failed to report Servlet invocation outcome", reportFailure);
        }
    }
}
