package com.lingframe.starter.filter;

import com.lingframe.core.invoker.InvocationAdmission;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 将实例准入凭据绑定到 Servlet 执行区间及异步完成事实。
 * 错误或超时通知不代表业务已结束；只有初始调用已返回且异步完成后才能释放。
 */
final class ServletInvocationAdmission implements AsyncListener, AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(ServletInvocationAdmission.class);
    private final InvocationAdmission admission;
    private boolean returned;
    private boolean asynchronous;
    private boolean completed;

    ServletInvocationAdmission(InvocationAdmission admission) {
        this.admission = admission;
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
        returned = true;
        releaseIfComplete();
    }

    @Override
    public synchronized void onComplete(AsyncEvent event) {
        completed = true;
        releaseIfComplete();
    }

    @Override
    public void onTimeout(AsyncEvent event) {
        // 超时只是通知，异步业务可能仍在运行。
    }

    @Override
    public void onError(AsyncEvent event) {
        // 错误不等同于完成，等待容器确认请求结束。
    }

    @Override
    public void onStartAsync(AsyncEvent event) {
        synchronized (this) {
            completed = false;
        }
        track(event.getAsyncContext());
    }

    private void releaseIfComplete() {
        if (returned && (!asynchronous || completed)) {
            admission.close();
        }
    }
}
