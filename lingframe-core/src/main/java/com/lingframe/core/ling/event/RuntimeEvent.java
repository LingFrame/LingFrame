package com.lingframe.core.ling.event;

import com.lingframe.core.ling.LingInstance;
import lombok.EqualsAndHashCode;
import lombok.Value;

/**
 * 单元运行时内部事件（组件间通信用）
 * 注意：这是内部事件，不暴露给外部
 */
public abstract class RuntimeEvent {

    // 私有构造，只能通过工厂方法创建
    private RuntimeEvent() {}

    public abstract String lingId();


    // ===== 生命周期事件 =====

    /**
     * 实例升级中（新版本即将启动）
     */
    @Value
    @EqualsAndHashCode(callSuper = false)
    public static class InstanceUpgrading extends RuntimeEvent {
        String lingId;
        String newVersion;

        public String lingId(){return lingId;}

        public String newVersion(){return newVersion;}

        @Override
        public String toString() {
            return String.format("InstanceUpgrading{lingId='%s', newVersion='%s'}",
                    lingId, newVersion);
        }
    }

    /**
     * 实例已就绪
     */
    @Value
    @EqualsAndHashCode(callSuper = false)
    public static class InstanceReady extends RuntimeEvent {
        String lingId;
        String version;
        LingInstance instance;

        public String lingId(){return lingId;}

        @Override
        public String toString() {
            return String.format("InstanceReady{lingId='%s', version='%s'}",
                    lingId, version);
        }
    }

    /**
     * 实例进入死亡状态
     */
    @Value
    @EqualsAndHashCode(callSuper = false)
    public static class InstanceDying extends RuntimeEvent {
        String lingId;
        String version;
        LingInstance instance;

        public String lingId(){return lingId;}
        public String version(){return version;}
        public LingInstance instance(){return instance;}

        @Override
        public String toString() {
            return String.format("InstanceDying{lingId='%s', version='%s'}",
                    lingId, version);
        }
    }

    /**
     * 实例已销毁
     */
    @Value
    @EqualsAndHashCode(callSuper = false)
    public static class InstanceDestroyed extends RuntimeEvent {
        String lingId;
        String version;

        public String lingId(){return lingId;}
        public String version(){return version;}

        @Override
        public String toString() {
            return String.format("InstanceDestroyed{lingId='%s', version='%s'}",
                    lingId, version);
        }
    }

    // ===== 运行时事件 =====

    /**
     * 运行时关闭中
     */
    @Value
    @EqualsAndHashCode(callSuper = false)
    public static class RuntimeShuttingDown extends RuntimeEvent {
        String lingId;

        public String lingId(){return lingId;}

        @Override
        public String toString() {
            return String.format("RuntimeShuttingDown{lingId='%s'}", lingId);
        }
    }

    /**
     * 运行时已关闭
     */
    @Value
    @EqualsAndHashCode(callSuper = false)
    public static class RuntimeShutdown extends RuntimeEvent {
        String lingId;

        public String lingId(){return lingId;}

        @Override
        public String toString() {
            return String.format("RuntimeShutdown{lingId='%s'}", lingId);
        }
    }

    /**
     * 调用开始
     */
    @Value
    @EqualsAndHashCode(callSuper = false)
    public static class InvocationStarted extends RuntimeEvent {
        String lingId;
        String fqsid;
        String caller;

        public String lingId(){return lingId;}
        public String fqsid(){return fqsid;}
        public String caller(){return caller;}

        @Override
        public String toString() {
            return String.format("InvocationStarted{lingId='%s', fqsid='%s', caller='%s'}",
                    lingId, fqsid, caller);
        }
    }

    /**
     * 调用完成
     */
    @Value
    @EqualsAndHashCode(callSuper = false)
    public static class InvocationCompleted extends RuntimeEvent {
        String lingId;
        String fqsid;
        long durationMs;
        boolean success;

        public String lingId(){return lingId;}
        public String fqsid(){return fqsid;}
        public long durationMs(){return durationMs;}
        public boolean success(){return success;}

        @Override
        public String toString() {
            return String.format("InvocationCompleted{lingId='%s', fqsid='%s', durationMs=%d, success=%s}",
                    lingId, fqsid, durationMs, success);
        }
    }

    /**
     * 调用被拒绝（舱壁满）
     */
    @Value
    @EqualsAndHashCode(callSuper = false)
    public static class InvocationRejected extends RuntimeEvent {
        String lingId;
        String fqsid;
        String reason;

        public String lingId(){return lingId;}
        public String fqsid(){return fqsid;}
        public String reason(){return reason;}

        @Override
        public String toString() {
            return String.format("InvocationRejected{lingId='%s', fqsid='%s', reason='%s'}",
                    lingId, fqsid, reason);
        }
    }
}