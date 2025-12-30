package com.lingframe.core.plugin;

import com.lingframe.api.context.PluginContext;
import com.lingframe.api.event.lifecycle.PluginStartedEvent;
import com.lingframe.api.event.lifecycle.PluginStartingEvent;
import com.lingframe.api.event.lifecycle.PluginStoppedEvent;
import com.lingframe.api.event.lifecycle.PluginStoppingEvent;
import com.lingframe.core.event.EventBus;
import com.lingframe.core.governance.DefaultTransactionVerifier;
import com.lingframe.core.invoker.FastPluginServiceInvoker;
import com.lingframe.core.kernel.GovernanceKernel;
import com.lingframe.core.kernel.InvocationContext;
import com.lingframe.core.monitor.TraceContext;
import com.lingframe.core.proxy.SmartServiceProxy;
import com.lingframe.core.spi.*;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 插件槽位：管理蓝绿发布与自然消亡
 */
@Slf4j
public class PluginSlot {

    // OOM 防御：最多保留5个历史快照
    private static final int MAX_HISTORY_SNAPSHOTS = 5;

    // 专门用于保护槽位状态变更的锁
    private final ReentrantLock stateLock = new ReentrantLock();

    // 用于记录是否已经调度了强制清理任务
    private final AtomicBoolean forceCleanupScheduled = new AtomicBoolean(false);

    @Getter
    private final String pluginId;

    // 实例池：支持多版本并存 [核心演进]
    private final CopyOnWriteArrayList<PluginInstance> activePool = new CopyOnWriteArrayList<>();
    // 默认实例引用 (用于保底路由和兼容旧逻辑)
    @Getter
    private final AtomicReference<PluginInstance> defaultInstance = new AtomicReference<>();

    // 死亡队列：存放待销毁的旧版本
    private final ConcurrentLinkedQueue<PluginInstance> dyingInstances = new ConcurrentLinkedQueue<>();

    // 代理缓存：Map<InterfaceClass, ProxyObject>
    private final Map<Class<?>, Object> proxyCache = new ConcurrentHashMap<>();

    // FQSID -> InvokableService 缓存 (用于协议服务)
    // 缓存 FQSID 对应的可执行方法和 Bean 实例
    private final Map<String, InvokableService> serviceMethodCache = new ConcurrentHashMap<>();

    private final GovernanceKernel governanceKernel;

    private final EventBus eventBus;

    private final ScheduledExecutorService sharedScheduler;

    private final TrafficRouter router;
    private final PluginServiceInvoker invoker;

    // 注入事务验证器
    private final TransactionVerifier transactionVerifier;

    private static final int DEFAULT_TIMEOUT_MS = 3000; // 默认超时 3 秒

    // 专用执行器，用于运行插件方法（隔离线程池）
    private final ExecutorService pluginExecutor;

    // 信号量实现“软隔离” (例如限制每个插件最大并发 10)
    private final Semaphore bulkhead = new Semaphore(10);

    // 需要注入所有的传播器
    private final List<ThreadLocalPropagator> propagators = new ArrayList<>();

    public PluginSlot(String pluginId,
                      ScheduledExecutorService sharedScheduler,
                      ExecutorService pluginExecutor,
                      GovernanceKernel governanceKernel,
                      EventBus eventBus,
                      TrafficRouter router,
                      PluginServiceInvoker invoker,
                      TransactionVerifier transactionVerifier,
                      List<ThreadLocalPropagator> propagators) {
        this.pluginId = pluginId;
        this.sharedScheduler = sharedScheduler;
        this.governanceKernel = governanceKernel;
        this.eventBus = eventBus;
        this.router = router;
        this.invoker = invoker;
        this.transactionVerifier = transactionVerifier != null ?
                transactionVerifier : new DefaultTransactionVerifier();
        // 防御性拷贝，防止外部修改
        if (propagators != null) {
            this.propagators.addAll(propagators);
        }
        // 清理任务调度器：共享的全局线程池
        // 每 5 秒检查一次是否有可以回收的旧实例
        if (sharedScheduler != null) {
            sharedScheduler.scheduleAtFixedRate(this::checkAndKill, 5, 5, TimeUnit.SECONDS);
        }

        // 外部注入的共享 executor
        this.pluginExecutor = pluginExecutor;
    }

    /**
     * 核心路由：支持标签匹配
     */
    public PluginInstance selectInstance(InvocationContext ctx) {
        return router.route(activePool, ctx);
    }

    public void addInstance(PluginInstance newInstance, PluginContext pluginContext, boolean isDefault) {
        // 【乐观检查】无锁快速背压检查，避免无效启动
        if (dyingInstances.size() >= MAX_HISTORY_SNAPSHOTS) {
            throw new IllegalStateException("System busy: Too many dying instances (Fast check failed).");
        }

        // 【无锁启动】耗时操作不占锁
        log.info("[{}] Starting new version: {}", pluginId, newInstance.getVersion());

        // 🔥Hook 1: Pre-Start 发送 Starting 事件
        // 如果有监听器抛出异常，addInstance 会在此中断，不会执行 container.start()
        eventBus.publish(new PluginStartingEvent(pluginId, newInstance.getVersion()));

        clearCaches();
        try {
            newInstance.getContainer().start(pluginContext);
            // 【关键】等待就绪或设置就绪
            // 这里假设容器启动是同步的，启动完即就绪
            // 如果是异步启动（如 Web 容器），需要在这里 Future.get() 或监听事件
            newInstance.markReady();
        } catch (Exception e) {
            log.error("[{}] Failed to start new version {}", pluginId, newInstance.getVersion(), e);
            try {
                newInstance.destroy();
            } catch (Exception ignored) {
            }
            throw new RuntimeException("Plugin start failed.", e);
        }

        // 【悲观确认】加锁进行状态切换
        stateLock.lock();
        try {
            // 再次检查背压（防止在启动期间队列满了）
            if (dyingInstances.size() >= MAX_HISTORY_SNAPSHOTS) {
                log.warn("[{}] Backpressure hit after startup. Killing newly started instance.", pluginId);
                try {
                    newInstance.destroy();
                } catch (Exception ignored) {
                }
                throw new IllegalStateException("System busy: Too many dying instances (Lock check failed).");
            }

            activePool.add(newInstance);

            if (isDefault) {
                if (!newInstance.isReady()) {
                    log.warn("[{}] New version is NOT READY. Keeping old version.", pluginId);
                    // 如果不 ready，不切换流量，直接把新实例干掉（或留在池子里作为灰度）
                    // 这里选择简单处理：如果不 Ready，直接销毁，回滚升级
                    activePool.remove(newInstance);
                    try {
                        newInstance.destroy();
                    } catch (Exception e) { /* ignore */ }
                    throw new IllegalStateException("New instance failed to become ready.");
                }
                PluginInstance old = defaultInstance.getAndSet(newInstance);
                if (old != null) {
                    moveToDying(old);// 安全，因为当前线程已持有 stateLock
                }
            }
        } finally {
            stateLock.unlock();
        }

        // 🔥Hook 2: Post-Start (通知监控系统)
        eventBus.publish(new PluginStartedEvent(pluginId, newInstance.getVersion()));
        log.info("[{}] Version {} started.", pluginId, newInstance.getVersion());
    }

    /**
     * 销毁实例 (带钩子)
     */
    private void destroyInstance(PluginInstance instance) {
        if (!instance.getContainer().isActive()) return;

        String version = instance.getVersion();
        log.info("[{}] Stopping version: {}", pluginId, version);

        // 🔥Hook 3: Pre-Stop (通知插件做优雅停机，如关闭连接池)
        // 注意：停止过程通常不建议抛异常打断，除非是强制无法停止
        try {
            eventBus.publish(new PluginStoppingEvent(pluginId, version));
        } catch (Exception e) {
            log.error("Error in Pre-Stop hook", e);
        }

        try {
            instance.destroy(); // 物理关闭
        } catch (Exception e) {
            log.error("Error destroying instance", e);
        }

        // 🔥Hook 4: Post-Stop (通知资源回收)
        eventBus.publish(new PluginStoppedEvent(pluginId, version));
    }

    private void moveToDying(PluginInstance instance) {
        instance.markDying();
        activePool.remove(instance);
        dyingInstances.add(instance);
    }

    /**
     * 获取服务的动态代理
     * 注意：返回的永远是同一个 Proxy 对象，但内部指向会变
     */
    @SuppressWarnings("unchecked")
    public <T> T getService(String callerPluginId, Class<T> interfaceClass) {
        return (T) proxyCache.computeIfAbsent(interfaceClass, k ->
                Proxy.newProxyInstance(
                        this.getClass().getClassLoader(), // 使用 Core 的 ClassLoader
                        new Class<?>[]{interfaceClass},
                        new SmartServiceProxy(
                                callerPluginId,// 谁在调
                                this,// 调谁 (就是当前 Slot) 🔥
                                interfaceClass,
                                governanceKernel
                        )
                ));
    }

    /**
     * 注册真实的可执行服务 (由 PluginManager 调用)
     */
    public void registerService(String fqsid, Object bean, Method method) {
        try {
            // 解除权限检查，提升性能
            method.setAccessible(true);
            // 转换为 MethodHandle (比反射快约 2-4 倍)
            MethodHandle methodHandle = MethodHandles.lookup().unreflect(method).bindTo(bean);
            serviceMethodCache.put(fqsid, new InvokableService(bean, method, methodHandle));
            log.debug("Registered optimized service: {}", fqsid);
        } catch (IllegalAccessException e) {
            throw new RuntimeException("Failed to create MethodHandle for " + fqsid, e);
        }
    }

    /**
     * 协议服务调用入口 (含超时控制与线程隔离)
     */
    public Object invokeService(String callerPluginId, String fqsid, Object[] args) throws Exception {
        PluginInstance instance = defaultInstance.get();
        if (instance == null || !instance.getContainer().isActive()) {
            throw new IllegalStateException("Service unavailable for FQSID: " + fqsid);
        }

        InvokableService invokable = getInvokableService(fqsid, instance.getContainer());
        if (invokable == null) {
            throw new NoSuchMethodException("FQSID not found in slot: " + fqsid);
        }

        // 事务/同步判断逻辑
        // 🔥 生产级实现：通过 SPI 判断
        boolean isTx = transactionVerifier.isTransactional(
                invokable.method(),
                invokable.bean().getClass() // 注意：要传 Bean 的实际类型，因为注解可能在类上
        );
        if (isTx) {
            // 🔥 同步模式：直接在当前线程执行，Spring 事务即可自动传播
            // 注意：这会绕过线程池隔离，可能导致宿主线程被阻塞，需权衡
            return executeInternal(instance, invokable, args);
        }

        // 异步模式：执行上下文搬运
        // [Step A] 主线程捕获快照
        Object[] snapshots = new Object[propagators.size()];
        for (int i = 0; i < propagators.size(); i++) {
            snapshots[i] = propagators.get(i).capture();
        }

        // 捕获当前线程的 TraceID
        String traceId = TraceContext.get();
        // 创建异步任务
        Callable<Object> task = () -> {
            // [Step B] 子线程重放快照
            Object[] backupContexts = new Object[propagators.size()];
            // 在子线程中重放 TraceID
            if (traceId != null) TraceContext.setTraceId(traceId);
            try {
                for (int i = 0; i < propagators.size(); i++) {
                    backupContexts[i] = propagators.get(i).replay(snapshots[i]);
                }
                // 执行业务
                return executeInternal(instance, invokable, args);
            } catch (Throwable e) {
                if (e instanceof Exception) throw (Exception) e;
                throw new RuntimeException(e);
            } finally {
                // [Step C] 子线程清理/恢复
                for (int i = 0; i < propagators.size(); i++) {
                    propagators.get(i).restore(backupContexts[i]);
                }
                // 清理子线程 ThreadLocal
                TraceContext.clear();
            }
        };

        // 申请许可证 (实现背压和隔离)
        if (!bulkhead.tryAcquire(DEFAULT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            throw new RejectedExecutionException("Plugin [" + pluginId + "] is busy (Bulkhead full).");
        }

        // 提交到隔离线程池
        Future<Object> future = pluginExecutor.submit(task);
        try {
            // 阻塞等待结果（带超时）
            return future.get(DEFAULT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            // 超时处理：中断工作线程（如果能响应中断的话）
            future.cancel(true);
            log.error("[LingFrame] Plugin execution timeout ({}ms). FQSID={}, Caller={}",
                    DEFAULT_TIMEOUT_MS, fqsid, callerPluginId);
            throw new RuntimeException("Plugin execution timeout", e);
        } catch (ExecutionException e) {
            // 业务异常处理：解包底层异常
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            throw new RuntimeException("Plugin execution failed", cause);
        } catch (InterruptedException e) {
            // 线程中断处理
            Thread.currentThread().interrupt();
            throw new RuntimeException("Plugin execution interrupted", e);
        } finally {
            // 释放许可证
            bulkhead.release();
        }
    }

    // 提取公共执行逻辑
    private Object executeInternal(PluginInstance instance, InvokableService invokable, Object[] args) throws Exception {
        try {
            if (invoker instanceof FastPluginServiceInvoker fast) {
                return fast.invokeFast(instance, invokable.methodHandle(), args);
            }
            return invoker.invoke(instance, invokable.bean(), invokable.method(), args);
        } catch (Throwable t) {
            if (t instanceof Exception) {
                throw (Exception) t;
            } else if (t instanceof Error) {
                throw (Error) t;
            } else {
                // 对于极少数既不是 Exception 也不是 Error 的 Throwable (如自定义子类)
                throw new RuntimeException("Execution failed with unknown Throwable", t);
            }
        }
    }

    /**
     * 获取可执行服务，严格执行“注册才能调用”
     */
    private InvokableService getInvokableService(String fqsid, PluginContainer container) {
        // 1. 优先从缓存获取
        InvokableService service = serviceMethodCache.get(fqsid);
        if (service != null) {
            return service;
        }

        // 2. 缓存未命中，这是严重错误
        // 正常情况下，PluginContainer.start() 时会扫描并注册所有服务。
        // 如果运行时找不到，说明启动流程有问题或 FQSID 拼写错误。
        log.error("[LingFrame] Critical Error: FQSID [{}] not found in service registry. " +
                "This indicates a registration failure during plugin startup.", fqsid);

        throw new IllegalStateException("Service not found: " + fqsid +
                ". Please check if the plugin started successfully.");
    }

    /**
     * 定时任务：检查并物理销毁旧实例
     */
    private void checkAndKill() {
        // 使用 tryLock 避免阻塞定时任务线程
        if (stateLock.tryLock()) {
            try {
                dyingInstances.removeIf(instance -> {
                    if (instance.isIdle()) {
                        destroyInstance(instance);
                        return true;
                    }
                    return false;
                });
            } finally {
                stateLock.unlock();
            }
        }
    }

    /**
     * 获取当前活跃版本号
     */
    public String getVersion() {
        PluginInstance instance = defaultInstance.get();
        return (instance != null) ? instance.getVersion() : null;
    }

    /**
     * 卸载整个槽位
     * 逻辑：
     * 1. 将 Active 实例置空 (切断新流量)
     * 2. 将原 Active 实例移入死亡队列 (处理剩余流量)
     * 3. 触发一次清理检查
     */
    public void uninstall() {
        stateLock.lock();
        try {
            // 切断流量
            activePool.forEach(this::moveToDying);
            defaultInstance.set(null);

            // 关闭线程池
            shutdownExecutor();

            // 清理缓存（彻底卸载）
            clearCaches();

            // 尝试立即清理一次
            checkAndKill();

            // 调度强制兜底任务（防止旧实例一直不归零）
            if (forceCleanupScheduled.compareAndSet(false, true)) {
                // 延迟 30 秒后执行强制清理
                sharedScheduler.schedule(this::forceKillAll, 30, TimeUnit.SECONDS);
            }
        } finally {
            stateLock.unlock();
        }
    }

    private void shutdownExecutor() {
        if (pluginExecutor != null && !pluginExecutor.isShutdown()) {
            log.info("[{}] Shutting down plugin executor...", pluginId);
            pluginExecutor.shutdown(); // 停止接受新任务
            try {
                // 等待现有任务结束（最多 10 秒）
                if (!pluginExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                    log.warn("[{}] Plugin executor did not terminate in time. Forcing shutdown.", pluginId);
                    pluginExecutor.shutdownNow(); // 强制中断
                }
            } catch (InterruptedException e) {
                pluginExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * 强制清理所有濒死实例（不管引用计数是否归零）
     */
    private void forceKillAll() {
        // 这里不需要加锁，因为已经是卸载流程的终点了
        log.warn("[{}] Force cleanup triggered. Destroying remaining instances.", pluginId);
        dyingInstances.removeIf(instance -> {
            destroyInstance(instance);
            return true;
        });
    }

    private void clearCaches() {
        serviceMethodCache.clear();
        proxyCache.clear();
    }

    // 用于缓存可执行的服务对象和方法
    public record InvokableService(Object bean, Method method, MethodHandle methodHandle) {
    }

    // 获取注册的方法元数据
    public InvokableService getProtocolService(String fqsid) {
        return serviceMethodCache.get(fqsid);
    }

    // 注册
    public void registerProtocolService(String fqsid, Object bean, Method method) {
        registerService(fqsid, bean, method);
    }

    // 执行
    public Object invokeProtocolService(InvokableService service, Object[] args) throws Exception {
        PluginInstance instance = selectInstance(null); // Context 已在 Kernel 处理，此处选 Active 即可
        if (instance == null) throw new IllegalStateException("No active instance");

        // 使用 Invoker 执行
        return invoker.invoke(instance, service.bean(), service.method(), args);
    }

    public boolean hasBean(Class<?> type) {
        try {
            PluginInstance instance = defaultInstance.get();
            if (instance == null || !instance.getContainer().isActive()) return false;

            // 需要在 PluginContainer 接口增加 containsBean(Class) 或者复用 getBean
            Object bean = instance.getContainer().getBean(type);
            return bean != null;
        } catch (Exception e) {
            return false; // 找不到或报错都算 false
        }
    }
}