package com.lingframe.core.plugin;

import com.lingframe.api.context.PluginContext;
import com.lingframe.api.security.PermissionService;
import com.lingframe.core.classloader.PluginClassLoader;
import com.lingframe.core.context.CorePluginContext;
import com.lingframe.core.dev.HotSwapWatcher;
import com.lingframe.core.event.EventBus;
import com.lingframe.core.security.DefaultPermissionService;
import com.lingframe.core.spi.ContainerFactory;
import com.lingframe.core.spi.PluginContainer;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.*;

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

    // 记录插件源路径，用于 reload
    private final Map<String, File> pluginSources = new ConcurrentHashMap<>();

    private final HotSwapWatcher hotSwapWatcher;

    // EventBus 用于插件间通信
    private final EventBus eventBus;

    public PluginManager(ContainerFactory containerFactory) {
        this.containerFactory = containerFactory;
        // 实际应单例注入
        this.permissionService = new DefaultPermissionService();
        // 初始化热加载器
        this.hotSwapWatcher = new HotSwapWatcher(this);
        eventBus = new EventBus();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "lingframe-plugin-cleaner");
            t.setDaemon(true); // 设置为守护线程，防止阻碍 JVM 关闭
            return t;
        });
    }

    /**
     * 安装 Jar 包插件 (生产模式)
     */
    public void install(String pluginId, String version, File jarFile) {
        log.info("Installing plugin: {} v{}", pluginId, version);
        pluginSources.put(pluginId, jarFile);
        doInstall(pluginId, version, jarFile);
    }

    /**
     * 安装目录插件 (开发模式)
     */
    public void installDev(String pluginId, String version, File classesDir) {
        if (!classesDir.exists() || !classesDir.isDirectory()) {
            throw new IllegalArgumentException("Invalid classes directory: " + classesDir);
        }
        log.info("Installing plugin in DEV mode: {} (Dir: {})", pluginId, classesDir.getName());

        // 注册热监听
        hotSwapWatcher.register(pluginId, classesDir);
        pluginSources.put(pluginId, classesDir);

        doInstall(pluginId, version, classesDir);
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
        doInstall(pluginId, "dev-reload-" + System.currentTimeMillis(), source);
    }

    /**
     * 安装或升级插件 (核心入口)
     * 支持热替换：如果插件已存在，则触发蓝绿部署流程
     *
     * @param pluginId 插件唯一标识
     * @param version  插件版本号
     * @param sourceFile 插件源文件 (Jar 包或目录)
     */
    public void doInstall(String pluginId, String version, File sourceFile) {
        try {
            // 1. 插件 ID 冲突检查
            if (slots.containsKey(pluginId)) {
                log.warn("[{}] Slot already exists. Preparing for upgrade.", pluginId);
            }
            // 准备隔离环境 (Child-First ClassLoader)
            ClassLoader pluginClassLoader = createPluginClassLoader(sourceFile);

            // SPI 构建容器 (此时仅创建配置，未启动)
            PluginContainer container = containerFactory.create(pluginId, sourceFile, pluginClassLoader);
            PluginInstance instance = new PluginInstance(version, container);

            // 获取或创建槽位
            PluginSlot slot = slots.computeIfAbsent(pluginId, k -> new PluginSlot(k, scheduler, permissionService));
            // 创建上下文
            PluginContext context = new CorePluginContext(pluginId, this, permissionService, eventBus);

            // 执行升级 (启动新容器 -> 原子切换流量 -> 旧容器进入死亡队列)
            slot.upgrade(instance, context);

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
        PluginSlot slot = slots.remove(pluginId);
        if (slot == null) {
            log.warn("Plugin not found: {}", pluginId);
            return;
        }

        // 1. 【新增】从中央注册表移除所有 FQSID
        unregisterProtocolServices(pluginId);

        // 委托槽位执行优雅下线
        slot.uninstall();
    }

    /**
     * 获取插件对外暴露的服务 (动态代理)
     *
     * @param callerPluginId    插件ID
     * @param serviceType 服务接口类型
     * @return 服务代理对象
     */
    public <T> T getService(String callerPluginId, Class<T> serviceType) {
        String serviceKey = serviceType.getName();

        // 遍历所有插件槽位，找到提供此服务的插件
        for (Map.Entry<String, PluginSlot> entry : slots.entrySet()) {
            String targetPluginId = entry.getKey();
            PluginSlot slot = entry.getValue();

            try {
                // 通过目标槽位的代理获取服务
                return slot.getService(callerPluginId, serviceType);
            } catch (Exception e) {
                // 继续尝试其他插件
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
     * @param fqsid 全路径服务ID (Plugin ID:Short ID)
     * @param args 参数列表
     * @return 方法执行结果
     */
    @SuppressWarnings("unchecked")
    public <T> Optional<T> invokeService(String callerPluginId, String fqsid, Object... args) {
        // 1. 查找路由目标插件
        String targetPluginId = protocolServiceRegistry.get(fqsid);
        if (targetPluginId == null) {
            log.warn("[{}] Service not found for FQSID: {}", callerPluginId, fqsid);
            return Optional.empty();
        }

        // 2. 获取目标槽位
        PluginSlot slot = slots.get(targetPluginId); //
        if (slot == null) {
            log.error("PluginSlot not found for PluginId: {}", targetPluginId);
            return Optional.empty();
        }

        // 3. 委托给 PluginSlot 执行路由调用
        try {
            // PluginSlot.invokeService 方法需要实现 FQSID 到 MethodHandle 的查找和执行
            Object result = slot.invokeService(callerPluginId, fqsid, args);
            // 修正错误：进行显式类型转换
            return Optional.ofNullable((T) result);
        } catch (Exception e) {
            log.error("[{}] Error invoking service {} in slot {}", callerPluginId, fqsid, targetPluginId, e);
            throw new RuntimeException("Protocol service invocation error: " + e.getMessage(), e);
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
}