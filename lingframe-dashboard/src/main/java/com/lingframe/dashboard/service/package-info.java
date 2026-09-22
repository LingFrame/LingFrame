/**
 * Dashboard 治理控制面服务层。
 * <p>
 * 本包内的类承担 Dashboard 与治理内核之间的边界翻译，分为三类角色：
 * <ul>
 *   <li><b>只读门面</b>：{@link com.lingframe.dashboard.service.DashboardService}
 *       —— 聚合多个协调器对外暴露统一查询 API（状态 / 指标 / 事件），不持有写权限。</li>
 *   <li><b>写侧协调器（Coordinator）</b>：唯一写入口，各自代理不同的下游内核 coordinator，
 *       不越权写入核心内部状态（手册第 5 章）：
 *     <ul>
 *       <li>{@link com.lingframe.dashboard.service.DashboardStatusCoordinator}
 *           —— 状态切换编排（ACTIVE / INACTIVE / RECOVERING / REMOVED 迁移 + 权限撤销 + 默认策略下发）。</li>
 *       <li>{@link com.lingframe.dashboard.service.DashboardLingOperations}
 *           —— 安装 / 卸载 / 热重载编排（含 per-lingId {@code ReentrantLock} 防并发竞态）。</li>
 *       <li>{@link com.lingframe.dashboard.service.DashboardGovernanceSupport}
 *           —— 治理策略 DTO 镜像 + patch 合并 / 权限同步，替代外围模块直接操作 {@code LocalGovernanceRegistry}。</li>
 *     </ul>
 *   </li>
 *   <li><b>事件驱动诊断与存储</b>：
 *     <ul>
 *       <li>{@link com.lingframe.dashboard.service.RuntimeDiagnosticsService}
 *           —— 订阅 EventBus 资源清理能力事件，计算治理就绪度并告警，独立于写侧协调器。</li>
 *       <li>{@link com.lingframe.dashboard.service.DashboardLifecycleEventStore}
 *           —— 生命周期事件存储（{@code addEvent / getEvents}，裁剪到 {@code MAX_EVENTS}），纯存储无写边界。</li>
 *     </ul>
 *   </li>
 * </ul>
 *
 * <h3>命名规范</h3>
 * <p>
 * 保留 {@code Coordinator} 命名——手册第 3 章 {@code Coordinator} 已有明确语义「唯一写入口」，
 * 改名 {@code Manager} 会与核心层 {@code DefaultLingLifecycleEngine} 呈命名空间冲突，
 * 且可能让读者误以为 dashboard 持有生命周期编排权（违反手册第 5 章 dashboard「不能越权写入核心内部状态」）。
 * 与只读门面 {@code DashboardService} 形成视觉区分即可，不重命名。
 *
 * <h3>合并约束</h3>
 * <p>
 * 不合并写侧协调器：{@code StatusCoordinator} 与 {@code LingOperations} 当前的分隔
 * 正是为了不让 Dashboard 越权写入核心内部状态，合并会把「操作下发」与「状态迁移触发」塞进一个对象，
 * 反而扩大写边界。
 */
package com.lingframe.dashboard.service;
