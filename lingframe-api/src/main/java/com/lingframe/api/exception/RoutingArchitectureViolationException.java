package com.lingframe.api.exception;

/**
 * 路由层架构违例异常。
 * <p>
 * 当路由层或迁移状态机的架构契约被违反时抛出。当前抛出场景：
 * <ul>
 *   <li>{@code MigrationStateHolder.startMigration} / {@code startIteration}：非独占态发起新的迁移或迭代</li>
 *   <li>{@code MigrationStateHolder.confirmPhaseTransition}：当前非二元候选态，或退出方排空校验未通过（activeRequests != 0）</li>
 *   <li>{@code MigrationStateHolder.rollbackPhaseTransition}：当前非二元候选态</li>
 *   <li>{@code DefaultLingLifecycleEngine.deploy}：目标契约处于非独占态，禁止叠加部署</li>
 *   <li>{@code ContractRoutingService}：迁移状态持有者未配置</li>
 * </ul>
 * <p>
 * 抛出时立即终止调用链并触发强告警，绝不静默降级。
 *
 * @author LingFrame
 */
public class RoutingArchitectureViolationException extends LingException {

    private static final long serialVersionUID = 1L;

    public RoutingArchitectureViolationException(String message) {
        super(message);
    }

    public RoutingArchitectureViolationException(String message, Throwable cause) {
        super(message, cause);
    }
}
