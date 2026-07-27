package com.lingframe.api.exception;

/**
 * 路由层架构违例异常。
 * <p>
 * 灵珑路由层的核心约束：同一功能（契约）同一时刻有且仅有两个候选 provider 参与路由。
 * 当以下场景发生时抛出：
 * <ul>
 *   <li>{@code ProviderWeightRouter.selectProvider} 入口检测到候选数 &gt; 2</li>
 *   <li>{@code LingServiceRegistry.registerProvider} 注册第 3 个 provider</li>
 *   <li>非独占态发起新的迁移或迭代</li>
 * </ul>
 * <p>
 * 抛出时立即终止调用链并触发强告警，绝不静默降级。
 *
 * @author lingframe
 */
public class RoutingArchitectureViolationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public RoutingArchitectureViolationException(String message) {
        super(message);
    }

    public RoutingArchitectureViolationException(String message, Throwable cause) {
        super(message, cause);
    }
}
