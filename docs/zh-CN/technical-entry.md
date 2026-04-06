# 灵珑技术入口

**面向长期运行 JVM 系统的运行时治理内核**

> 当前对外公开实现基线

灵珑当前并不试图把单体系统一口气改造成分布式平台。  
当前代码真正聚焦的是：在不强迫系统重写的前提下，让长期运行中的 JVM 应用重新变得可治理。

项目重心已经明显从“零散治理能力”转向“收敛后的运行时内核”。

如果要用一句更容易建立项目识别度的话概括当前实现，可以这样理解：

> 灵珑现在不只是在证明“灵元可以被动态加载”，而是在正式回答“灵元能不能被规范地卸载、清理，并让长期运行秩序继续收得住”。

这一页是**源码阅读入口**，不是完整架构规格说明。

---

## 当前阶段到底意味着什么

当前公开版本主要围绕四件事展开：

- 以 `InvocationPipelineEngine` 为核心收敛统一治理执行主链
- 以 `InstanceStatus` / `RuntimeStatus` 建立正式的双层运行时状态模型
- 让灵元调用、Web 请求、灵核 Bean、Dashboard 模拟共用同一套治理内核
- 通过 trace、监控事件、SSE、泄漏诊断提升控制面的解释能力

如果你第一次读这个项目，建议始终带着这个视角往下看。

---

## 当前代码里已经落地的核心能力

| 能力 | 当前已实现内容 | 主要锚点 |
| :-- | :-- | :-- |
| 统一调用治理 | 显式 Filter 主链并在启动时校验顺序，`InvocationContext` 按 `routing/resolution/governance/execution` 降解魔术键 | `InvocationPipelineEngine`, `InvocationContext` |
| 运行时状态收敛 | 实例生命周期与宏观运行时可用性分层建模，并通过事件联动 | `InstanceStatus`, `RuntimeStatus`, `InstanceCoordinator`, `RuntimeCoordinator` |
| Web 治理 | Spring Boot 2 / 3 请求入口可通过 `GOVERN_ONLY` 借道内核 | `LingWebGovernanceFilter` |
| Bean 治理 | 灵核 Bean 通过 AOP 复用 Pipeline | `LingCoreBeanGovernanceInterceptor` |
| 模拟与解释 | Dashboard 通过 `SIMULATION` 运行真实治理链路 | `SimulateService`, `EngineTrace` |
| 事件流 | trace、audit、lifecycle、circuit breaker、leak 事件可通过 SSE 持续输出 | `MonitoringEvents`, `LogStreamService` |
| 长期运行清理 | 卸载时驱逐治理资源，并补充泄漏检测 | `InvocationPipelineEngine.evictLingResources`, `DefaultLeakDetector` |
| 生命周期编排 | 部署、旁路重载、排空后卸载、最终清理由统一运行时路径协调 | `DefaultLingLifecycleEngine`, `LingUnloadCoordinator` |
| Shared API 边界 | 共享契约先 preload，再 freeze，最后再加载灵元 | `SharedApiManager` |

---

## 最值得优先关注的项目特征

如果你第一次读源码，最值得优先建立的不是“它支持多少治理点”，而是下面四个判断：

- 灵珑关注的是**长期运行秩序**，不是一次性部署成功
- 灵珑强调的是**规范热卸载**，不只是动态加载
- **卸载清理、资源驱逐、泄漏检测**已经纳入正式运行时职责
- 灵珑对 `Shared API` 这种**进程级契约边界**保持克制，不会为了宣传热更能力而模糊风险

---

## 怎么读当前工程

| 模块 | 建议先看什么 |
| :-- | :-- |
| `lingframe-api` | 公开契约面与共享词汇 |
| `lingframe-core` | 真正的治理内核与运行时收敛点 |
| `lingframe-runtime` | Spring Boot 2 / 3 如何复用治理内核 |
| `lingframe-dashboard` | 控制面如何消费真实内核证据 |
| `lingframe-infrastructure` | 当前最清晰的存储 / 缓存代理参考路径 |
| `lingframe-examples` | 把文档与可运行示例连接起来的最快入口 |

如需完整模块职责说明，请回到 [架构设计](architecture.md)。

---

## 当前公开边界

当前代码刻意维持以下边界：

- 灵珑仍然是**单进程**运行时治理系统，不是分布式治理平台
- `Shared API` 仍然按**进程级契约**对待：新共享 JAR 可以热加载，但已加载共享契约变更仍需要重启进程
- 启动顺序本身也是契约边界的一部分：先 preload Shared API，再 freeze，最后加载灵元
- 当前对外公开的是治理主链收敛、运行时状态收敛、Dashboard 模拟与长期运行稳定性工作
- **真实流量无损回放验证不属于当前对外公开能力**
- 消息代理、搜索代理等更广的生态扩展仍属于后续工作，不应被视为现有完成能力

如果你接下来想看正式架构说明，请读 [架构设计](architecture.md)；  
如果你最关心状态写入权和联动链路，请直接读 [运行时双层状态机架构设计](runtime-dual-state-machine-architecture.md)。
