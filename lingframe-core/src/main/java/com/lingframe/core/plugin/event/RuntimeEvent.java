package com.lingframe.core.plugin.event;

import com.lingframe.core.plugin.PluginInstance;
import lombok.EqualsAndHashCode;
import lombok.Value;

/**
 * 插件运行时内部事件（组件间通信用）
 * 注意：这是内部事件，不暴露给外部
 */
public abstract class RuntimeEvent {

    // 私有构造，只能通过工厂方法创建
    private RuntimeEvent() {}

    public abstract String pluginId();


    // ===== 生命周期事件 =====

    /**
     * 实例升级中（新版本即将启动）
     */
    @Value
    @EqualsAndHashCode(callSuper = false)
    public static class InstanceUpgrading extends RuntimeEvent {
        String pluginId;
        String newVersion;

        public String pluginId(){return pluginId;}

        public String newVersion(){return newVersion;}

        @Override
        public String toString() {
            return String.format("InstanceUpgrading{pluginId='%s', newVersion='%s'}",
                    pluginId, newVersion);
        }
    }

    /**
     * 实例已就绪
     */
    @Value
    @EqualsAndHashCode(callSuper = false)
    public static class InstanceReady extends RuntimeEvent {
        String pluginId;
        String version;
        PluginInstance instance;

        public String pluginId(){return pluginId;}

        @Override
        public String toString() {
            return String.format("InstanceReady{pluginId='%s', version='%s'}",
                    pluginId, version);
        }
    }

    /**
     * 实例进入死亡状态
     */
    @Value
    @EqualsAndHashCode(callSuper = false)
    public static class InstanceDying extends RuntimeEvent {
        String pluginId;
        String version;
        PluginInstance instance;

        public String pluginId(){return pluginId;}
        public String version(){return version;}
        public PluginInstance instance(){return instance;}

        @Override
        public String toString() {
            return String.format("InstanceDying{pluginId='%s', version='%s'}",
                    pluginId, version);
        }
    }

    /**
     * 实例已销毁
     */
    @Value
    @EqualsAndHashCode(callSuper = false)
    public static class InstanceDestroyed extends RuntimeEvent {
        String pluginId;
        String version;

        public String pluginId(){return pluginId;}
        public String version(){return version;}

        @Override
        public String toString() {
            return String.format("InstanceDestroyed{pluginId='%s', version='%s'}",
                    pluginId, version);
        }
    }

    // ===== 运行时事件 =====

    /**
     * 运行时关闭中
     */
    @Value
    @EqualsAndHashCode(callSuper = false)
    public static class RuntimeShuttingDown extends RuntimeEvent {
        String pluginId;

        public String pluginId(){return pluginId;}

        @Override
        public String toString() {
            return String.format("RuntimeShuttingDown{pluginId='%s'}", pluginId);
        }
    }

    /**
     * 运行时已关闭
     */
    @Value
    @EqualsAndHashCode(callSuper = false)
    public static class RuntimeShutdown extends RuntimeEvent {
        String pluginId;

        public String pluginId(){return pluginId;}

        @Override
        public String toString() {
            return String.format("RuntimeShutdown{pluginId='%s'}", pluginId);
        }
    }

    /**
     * 调用开始
     */
    @Value
    @EqualsAndHashCode(callSuper = false)
    public static class InvocationStarted extends RuntimeEvent {
        String pluginId;
        String fqsid;
        String caller;

        public String pluginId(){return pluginId;}
        public String fqsid(){return fqsid;}
        public String caller(){return caller;}

        @Override
        public String toString() {
            return String.format("InvocationStarted{pluginId='%s', fqsid='%s', caller='%s'}",
                    pluginId, fqsid, caller);
        }
    }

    /**
     * 调用完成
     */
    @Value
    @EqualsAndHashCode(callSuper = false)
    public static class InvocationCompleted extends RuntimeEvent {
        String pluginId;
        String fqsid;
        long durationMs;
        boolean success;

        public String pluginId(){return pluginId;}
        public String fqsid(){return fqsid;}
        public long durationMs(){return durationMs;}
        public boolean success(){return success;}

        @Override
        public String toString() {
            return String.format("InvocationCompleted{pluginId='%s', fqsid='%s', durationMs=%d, success=%s}",
                    pluginId, fqsid, durationMs, success);
        }
    }

    /**
     * 调用被拒绝（舱壁满）
     */
    @Value
    @EqualsAndHashCode(callSuper = false)
    public static class InvocationRejected extends RuntimeEvent {
        String pluginId;
        String fqsid;
        String reason;

        public String pluginId(){return pluginId;}
        public String fqsid(){return fqsid;}
        public String reason(){return reason;}

        @Override
        public String toString() {
            return String.format("InvocationRejected{pluginId='%s', fqsid='%s', reason='%s'}",
                    pluginId, fqsid, reason);
        }
    }
}