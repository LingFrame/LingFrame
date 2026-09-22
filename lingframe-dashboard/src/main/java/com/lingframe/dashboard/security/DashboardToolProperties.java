package com.lingframe.dashboard.security;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Dashboard 演练类能力开关。
 * <p>
 * 这些能力可能触发真实业务调用、持续请求或运行时模式切换，生产装配默认全部关闭。
 * 只读开关仍是另一层全局保护，二者同时生效。
 */
@Data
@ConfigurationProperties(prefix = "lingframe.dashboard.tools")
public class DashboardToolProperties {

    /** 是否允许 Playground 发起 NORMAL 真实业务调用。 */
    private boolean realInvocationEnabled = false;

    /** 是否允许资源和 IPC 模拟接口。 */
    private boolean simulationEnabled = false;

    /** 是否允许压力路由和契约穿透演练。 */
    private boolean stressTestEnabled = false;

    /** 是否允许通过 Dashboard 切换运行时 dev/prod 模式。 */
    private boolean modeSwitchEnabled = false;

    /** 为直接构造控制器的兼容场景提供旧行为；Spring Boot 自动装配不会调用该方法。 */
    public static DashboardToolProperties directUseDefaults() {
        DashboardToolProperties properties = new DashboardToolProperties();
        properties.setRealInvocationEnabled(true);
        properties.setSimulationEnabled(true);
        properties.setStressTestEnabled(true);
        properties.setModeSwitchEnabled(true);
        return properties;
    }
}
