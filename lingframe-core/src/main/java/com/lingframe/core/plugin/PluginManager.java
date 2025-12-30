package com.lingframe.core.plugin;

import com.lingframe.api.config.PluginDefinition;
import com.lingframe.api.context.PluginContext;
import com.lingframe.api.event.lifecycle.PluginInstalledEvent;
import com.lingframe.api.event.lifecycle.PluginInstallingEvent;
import com.lingframe.api.event.lifecycle.PluginUninstalledEvent;
import com.lingframe.api.event.lifecycle.PluginUninstallingEvent;
import com.lingframe.api.security.AccessType;
import com.lingframe.api.security.PermissionService;
import com.lingframe.core.classloader.PluginClassLoader;
import com.lingframe.core.context.CorePluginContext;
import com.lingframe.core.event.EventBus;
import com.lingframe.core.governance.DefaultTransactionVerifier;
import com.lingframe.core.kernel.GovernanceKernel;
import com.lingframe.core.kernel.InvocationContext;
import com.lingframe.core.loader.PluginManifestLoader;
import com.lingframe.core.proxy.GlobalServiceRoutingProxy;
import com.lingframe.core.spi.*;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.FileInputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 插件生命周期管理器
 * 职责：
 * 1. 插件的安装与升级 (Install/Upgrade)
 * 2. 插件的卸载 (Uninstall)
 * 3. 服务的路由与发现 (Service Discovery)
 * 4. 资源的全局管控 (Global Shutdown)
 */
@Slf4j
public class PluginManager {

    private final ContainerFactory containerFactory;

    // 插件槽位表：Key=PluginId, Value=Slot
    private final Map<String, PluginSlot> slots = new ConcurrentHashMap<>();

    // 协议服务注册表：Key=FQSID (Fully Qualified Service ID), Value=PluginId
    private final Map<String, String> protocolServiceRegistry = new ConcurrentHashMap<>();

    // 全局清理调度器 (单线程即可，任务很轻)
    private final ScheduledExecutorService scheduler;

    // 权限服务
    private final PermissionService permissionService;

    // 内核
    private final GovernanceKernel governanceKernel;

    private final PluginLoaderFactory pluginLoaderFactory;

    // 安全验证器
    private final List<PluginSecurityVerifier> verifiers;

    // 记录插件源路径，用于 reload
    private final Map<String, File> pluginSources = new ConcurrentHashMap<>();

    // EventBus 用于插件间通信
    private final EventBus eventBus;

    private final TrafficRouter trafficRouter;
    private final PluginServiceInvoker pluginServiceInvoker;

    // 事务验证器
    private final TransactionVerifier transactionVerifier;

    private final List<ThreadLocalPropagator> propagators;

    // 全局执行器，用于运行插件方法（隔离线程池）
    private final ExecutorService pluginExecutor;

    // ================= 线程池配置 =================
    private static final int CORE_POOL_SIZE = Runtime.getRuntime().availableProcessors();
    private static final int MAX_POOL_SIZE = CORE_POOL_SIZE * 2;
    private static final int QUEUE_CAPACITY = 100; // 有界队列，防止无限积压导致 OOM
    private static final long KEEP_ALIVE_TIME = 60L;
    // 用于生成线程名的计数器
    private final AtomicInteger threadNumber = new AtomicInteger(1);

    public PluginManager(ContainerFactory containerFactory,
                         PermissionService permissionService,
                         GovernanceKernel governanceKernel,
                         PluginLoaderFactory pluginLoaderFactory,
                         List<PluginSecurityVerifier> verifiers,
                         EventBus eventBus,
                         TrafficRouter trafficRouter,
                         PluginServiceInvoker pluginServiceInvoker,
                         TransactionVerifier transactionVerifier,
                         List<ThreadLocalPropagator> propagators) {
        this.containerFactory = containerFactory;
        this.permissionService = permissionService;
        this.governanceKernel = governanceKernel;
        this.pluginLoaderFactory = pluginLoaderFactory;
        this.verifiers = verifiers != null ? verifiers : Collections.emptyList(); // 防御性处理
        this.eventBus = eventBus;
        this.trafficRouter = trafficRouter;
        this.pluginServiceInvoker = pluginServiceInvoker;
        this.transactionVerifier = transactionVerifier != null ?
                transactionVerifier : new DefaultTransactionVerifier();
        this.propagators = propagators != null ? propagators : Collections.emptyList(); // 防御性处理
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "lingframe-plugin-cleaner");
            thread.setDaemon(true); // 设置为守护线程，防止阻碍 JVM 关闭
            thread.setUncaughtExceptionHandler((t, e) ->
                    log.error("线程池线程 {} 异常: {}", t.getName(), e.getMessage()));
            return thread;
        });

        this.pluginExecutor = new ThreadPoolExecutor(
                CORE_POOL_SIZE,
                MAX_POOL_SIZE,
                KEEP_ALIVE_TIME, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(QUEUE_CAPACITY), // 关键：有界队列
                // 【原生 Java 实现】自定义线程工厂
                r -> {
                    Thread t = new Thread(r);
                    // 设置线程名：plugin-executor-{序号}
                    t.setName("plugin-executor-" + threadNumber.getAndIncrement());
                    // 设置为守护线程，不阻止 JVM 退出
                    t.setDaemon(true);
                    // 设置优先级（可选，生产级通常保持默认 NORMAL）
                    // t.setPriority(Thread.NORM_PRIORITY);
                    return t;
                },
                new ThreadPoolExecutor.AbortPolicy() // 关键：满载时快速失败，不阻塞宿主线程
        );
    }

    /**
     * 安装 Jar 包插件 (生产模式)
     */
    public void install(String pluginId, String version, File jarFile) {
        log.info("Installing plugin: {} v{}", pluginId, version);
        pluginSources.put(pluginId, jarFile);
        doInstall(pluginId, version, jarFile, true);
    }

    /**
     * 安装目录插件 (开发模式)
     */
    public void installDev(String pluginId, String version, File classesDir) {
        if (!classesDir.exists() || !classesDir.isDirectory()) {
            throw new IllegalArgumentException("Invalid classes directory: " + classesDir);
        }
        log.info("Installing plugin in DEV mode: {} (Dir: {})", pluginId, classesDir.getName());
        pluginSources.put(pluginId, classesDir);

        doInstall(pluginId, version, classesDir, true);
    }

    /**
     * 金丝雀/灰度发布入口
     *
     * @param labels 实例的固有标签
     */
    public void deployCanary(String pluginId, String version, File source, Map<String, String> labels) {
        doInstall(pluginId, version, source, false, labels);
    }

    private void doInstall(String pluginId, String version, File source, boolean isDefault) {
        doInstall(pluginId, version, source, isDefault, new HashMap<>());
    }

    /**
     * 重载插件 (热替换)
     */
    public void reload(String pluginId) {
        File source = pluginSources.get(pluginId);
        if (source == null) {
            log.warn("Cannot reload plugin {}: source not found", pluginId);
            return;
        }

        log.info("Reloading plugin: {}", pluginId);
        // 使用 dev-reload 作为版本号，或者从外部获取
        doInstall(pluginId, "dev-reload-" + System.currentTimeMillis(), source, true);
    }

    /**
     * 安装或升级插件 (核心入口)
     * 支持热替换：如果插件已存在，则触发蓝绿部署流程
     *
     * @param pluginId   插件唯一标识
     * @param version    插件版本号
     * @param sourceFile 插件源文件 (Jar 包或目录)
     */
    private void doInstall(String pluginId, String version, File sourceFile, boolean isDefault, Map<String, String> labels) {
        // 触发安装前置事件 (Hooks)
        eventBus.publish(new PluginInstallingEvent(pluginId, version, sourceFile));
        try {
            // 执行所有安全验证器
            if (verifiers != null) {
                for (PluginSecurityVerifier verifier : verifiers) {
                    verifier.verify(pluginId, sourceFile); // 失败直接抛异常退出
                }
            }

            // 插件 ID 冲突检查
            if (slots.containsKey(pluginId)) {
                log.warn("[{}] Slot already exists. Preparing for upgrade.", pluginId);
            }

            // 加载 plugin.yml 配置
            PluginDefinition definition = null;
            // 简单处理：如果是 Jar 包，需要解压读取；如果是目录，直接读。这里简化假设是目录或 Jar 内已处理
            // 在生产环境中，通常在 ContainerFactory 内部处理，这里为了演示逻辑
            File ymlFile = new File(sourceFile, "plugin.yml");
            if (ymlFile.exists()) {
                try (FileInputStream fis = new FileInputStream(ymlFile)) {
                    definition = PluginManifestLoader.load(fis);
                }
            }
            // 如果 definition 为空，创建一个默认的
            if (definition == null) definition = new PluginDefinition();
            definition.setId(pluginId);
            definition.setVersion(version);

            // 准备隔离环境 (Child-First ClassLoader)
            ClassLoader pluginClassLoader = pluginLoaderFactory.create(sourceFile, this.getClass().getClassLoader());

            // SPI 构建容器 (此时仅创建配置，未启动)
            PluginContainer container = containerFactory.create(pluginId, sourceFile, pluginClassLoader);
            PluginInstance instance = new PluginInstance(version, container);
            // 写入标签
            instance.getLabels().putAll(labels);
            instance.setDefinition(definition); // [设置定义]

            // 获取或创建槽位
            PluginSlot slot = slots.computeIfAbsent(pluginId,
                    k -> new PluginSlot(k, scheduler, pluginExecutor,
                            governanceKernel, eventBus, trafficRouter, pluginServiceInvoker,
                            transactionVerifier, propagators));
            // 创建上下文
            PluginContext context = new CorePluginContext(pluginId, this, permissionService, eventBus);

            // 执行新增 (启动新容器 -> 原子切换流量 -> 旧容器进入死亡队列)
            slot.addInstance(instance, context, isDefault);

            // 触发安装完成事件
            eventBus.publish(new PluginInstalledEvent(pluginId, version));
            log.info("Plugin installed successfully: {}", pluginId);
        } catch (Exception e) {
            log.error("Failed to install/reload plugin: {} v{}", pluginId, version, e);
            // 抛出运行时异常，通知上层调用失败
            throw new RuntimeException("Plugin install failed: " + e.getMessage(), e);
        }
    }

    /**
     * 卸载插件
     * 逻辑：将当前活跃实例标记为濒死，从管理列表中移除，等待引用计数归零后自然销毁
     *
     * @param pluginId 插件ID
     */
    public void uninstall(String pluginId) {
        log.info("Uninstalling plugin: {}", pluginId);
        // 🔥Hook 1: Pre-Uninstall (可被拦截，例如防止误删核心插件)
        eventBus.publish(new PluginUninstallingEvent(pluginId));

        PluginSlot slot = slots.remove(pluginId);
        if (slot == null) {
            log.warn("Plugin not found: {}", pluginId);
            return;
        }

        // 委托槽位执行优雅下线
        slot.uninstall();

        // 从中央注册表移除所有 FQSID
        unregisterProtocolServices(pluginId);

        // 清理事件监听器
        eventBus.unsubscribeAll(pluginId);

        // 清理权限数据
        permissionService.removePlugin(pluginId);

        // 🔥Hook 2: Post-Uninstall (清理配置、删除临时文件)
        eventBus.publish(new PluginUninstalledEvent(pluginId));
    }

    /**
     * 获取插件对外暴露的服务 (动态代理)
     *
     * @param callerPluginId 插件ID
     * @param serviceType    服务接口类型
     * @return 服务代理对象
     */
    public <T> T getService(String callerPluginId, Class<T> serviceType) {
        String serviceKey = serviceType.getName();

        // 遍历所有插件槽位，找到提供此服务的插件
        for (Map.Entry<String, PluginSlot> entry : slots.entrySet()) {
            PluginSlot slot = entry.getValue();

            if (!slot.hasBean(serviceType)) continue;

            try {
                // 通过目标槽位的代理获取服务
                return slot.getService(callerPluginId, serviceType);
            } catch (Exception e) {
                // 继续尝试其他插件
                log.error("Failed to get service {} from plugin {}: {}", serviceKey, entry.getKey(), e.getMessage(), e);
            }
        }

        throw new IllegalArgumentException("Service not found:  " + serviceKey);
    }

    // 供 CorePluginContext 回调使用
    public <T> T getService(String callerPluginId, String targetPluginId, Class<T> serviceType) {
        PluginSlot slot = slots.get(targetPluginId);
        if (slot == null) throw new IllegalArgumentException("Target plugin not found");
        return slot.getService(callerPluginId, serviceType);
    }

    /**
     * 获取当前已安装的所有插件ID
     */
    public Set<String> getInstalledPlugins() {
        return slots.keySet();
    }

    /**
     * 获取指定插件当前活跃的版本号
     */
    public String getPluginVersion(String pluginId) {
        PluginSlot slot = slots.get(pluginId);
        return (slot != null) ? slot.getVersion() : null;
    }

    /**
     * 全局关闭
     * 应用退出时调用，强制销毁所有资源
     */
    public void shutdown() {
        log.info("Shutting down PluginManager...");

        // 1. 停止清理任务
        scheduler.shutdownNow();

        // 2. 并行销毁所有槽位 (加速关闭过程)
        slots.values().parallelStream().forEach(slot -> {
            try {
                slot.uninstall(); // 触发销毁逻辑
            } catch (Exception e) {
                log.error("Error shutting down plugin slot", e);
            }
        });

        slots.clear();
        log.info("PluginManager shutdown complete.");
    }

    /**
     * 处理协议调用 (由 CorePluginContext.invoke 调用)
     *
     * @param callerPluginId 调用方插件ID (用于审计)
     * @param fqsid          全路径服务ID (Plugin ID:Short ID)
     * @param args           参数列表
     * @return 方法执行结果
     */
    @SuppressWarnings("unchecked")
    public <T> Optional<T> invokeService(String callerPluginId, String fqsid, Object... args) {
        // 查找目标
        String targetPluginId = protocolServiceRegistry.get(fqsid);
        if (targetPluginId == null) {
            log.warn("Service not found in registry: {}", fqsid);
            return Optional.empty();
        }

        PluginSlot slot = slots.get(targetPluginId);
        if (slot == null) {
            log.warn("Target plugin slot not found: {}", targetPluginId);
            return Optional.empty();
        }

        // 获取目标方法 (用于治理仲裁)
        PluginSlot.InvokableService invokable = slot.getProtocolService(fqsid);
        if (invokable == null) {
            log.warn("Method not registered in slot: {}", fqsid);
            return Optional.empty();
        }

        // 构建上下文
        InvocationContext ctx = InvocationContext.builder()
                .callerPluginId(callerPluginId)
                .pluginId(targetPluginId)
                .resourceType("RPC_HOST_INVOKE")
                .resourceId(fqsid)
                .operation(invokable.method().getName())
                .args(args)
                .requiredPermission(fqsid) // 权限即 ServiceID
                .accessType(AccessType.EXECUTE)
                .shouldAudit(true)
                .auditAction("HostInvoke:" + fqsid)
                .labels(Collections.emptyMap())
                .build();

        try {
            Object result = governanceKernel.invoke(slot, invokable.method(), ctx, () -> {
                try {
                    return slot.invokeService(callerPluginId, fqsid, args);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            return Optional.ofNullable((T) result);
        } catch (Exception e) {
            log.error("Invoke failed", e);
            return Optional.empty();
        }
    }

    /**
     * 供 Runtime 层调用的注册通道
     * 接收真实的 Bean 和 Method 引用
     */
    public void registerProtocolService(String pluginId, String fqsid, Object bean, Method method) {
        // 1. 注册路由表 (FQSID -> PluginId)
        if (protocolServiceRegistry.containsKey(fqsid)) {
            String existing = protocolServiceRegistry.get(fqsid);
            if (!existing.equals(pluginId)) {
                log.warn("FQSID Conflict! [{}] owned by [{}] is being overwritten by [{}]", fqsid, existing, pluginId);
            }
        }
        protocolServiceRegistry.put(fqsid, pluginId);

        // 2. 注册到 Slot 的执行缓存 (FQSID -> MethodHandle)
        PluginSlot slot = slots.get(pluginId);
        if (slot != null) {
            slot.registerService(fqsid, bean, method);
        }

        log.info("[{}] Registered Service: {}", pluginId, fqsid);
    }

    /**
     * 从中央注册表移除 FQSID
     */
    private void unregisterProtocolServices(String pluginId) { //
        protocolServiceRegistry.entrySet().removeIf(entry -> {
            if (entry.getValue().equals(pluginId)) {
                log.info("[{}] Unregistered FQSID: {}", pluginId, entry.getKey());
                return true;
            }
            return false;
        });
    }

    /**
     * 创建插件专用的 Child-First 类加载器
     */
    private ClassLoader createPluginClassLoader(File file) {
        try {
            URL[] urls = new URL[]{file.toURI().toURL()};
            // Parent 设置为 PluginManager 的类加载器 (通常是 AppClassLoader)
            return new PluginClassLoader(urls, this.getClass().getClassLoader());
        } catch (Exception e) {
            throw new RuntimeException("Failed to create classloader for " + file.getName(), e);
        }
    }

    /**
     * [新增] 检查指定插件是否包含某个类型的 Bean
     * 用于 GlobalProxy 在运行时动态探测
     */
    public boolean hasBean(String pluginId, Class<?> beanType) {
        PluginSlot slot = slots.get(pluginId);
        if (slot == null) return false;

        // 我们需要深入到 PluginSlot -> PluginInstance -> PluginContainer 去检查
        // 这需要在 PluginSlot 和 PluginContainer 接口中增加相应方法
        // 临时方案：直接获取一下试试，看是否返回 null (SpringContainer如果找不到通常返回null)
        return slot.hasBean(beanType);
    }

    /**
     * [重写] 获取服务的全局路由代理 (宿主专用)
     */
    @SuppressWarnings("unchecked")
    public <T> T getGlobalServiceProxy(String callerPluginId, Class<T> serviceType, String targetPluginId) {
        // 允许 targetPluginId 为 null 或插件暂未安装
        return (T) Proxy.newProxyInstance(
                this.getClass().getClassLoader(),
                new Class[]{serviceType},
                new GlobalServiceRoutingProxy(
                        callerPluginId,
                        serviceType,
                        targetPluginId,
                        this,
                        governanceKernel
                )
        );
    }

    public Collection<String> getAllPluginIds() {
        return Collections.unmodifiableSet(slots.keySet());
    }

    /**
     * 提供给 Proxy 使用的 Slot 访问器
     */
    public PluginSlot getSlot(String pluginId) {
        return slots.get(pluginId);
    }

}