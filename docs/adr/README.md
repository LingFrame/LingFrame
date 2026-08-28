# LingFrame 架构决策记录 (Architecture Decision Records, ADR)

本目录记录 LingFrame 核心架构演进过程中的关键技术决策、推演背景与长远影响。每个 ADR 都遵循经典不可篡改规范，为系统提供透明、可追溯的“决策上下文”。

## ADR 索引列表

| 编号 | 标题 | 状态 | 决策日期 |
|---|---|---|---|
| [ADR-0001](0001-lifecycle-lock-centralization.md) | 机制统一收归内核与策略上层编排（生命周期分段锁与 `withLifecycleLock`） | **Accepted** | 2026-08-20 |
| [ADR-0002](0002-microkernel-neutrality-and-spi.md) | 保持微内核纯粹性与 SPI 容器扩展（拒绝内核强绑定多容器） | **Accepted** | 2026-08-20 |
| [ADR-0003](0003-lingcore-baseline-and-governed-pipeline.md) | 灵核永久基线与灵元动态生命周期的非对称统一管道路由 | **Accepted** | 2026-08-20 |
| [ADR-0004](0004-defensive-unload-and-leak-detection.md) | 动态类加载防御性排空（Drain）、濒死队列与弱引用泄漏检测机制 | **Accepted** | 2026-08-20 |

---

## ADR 结构规范

每个 ADR 文件应包含以下段落：
1. **标题 (Title)**：`ADR-XXXX: 简明扼要的决策名称`
2. **状态 (Status)**：`Proposed` / `Accepted` / `Deprecated` / `Superseded by ADR-YYYY`
3. **背景与问题 (Context & Problem Statement)**：面临什么技术挑战？为什么现状不可持续？
4. **决策驱动因素 (Decision Drivers)**：第一性原理考量、KISS 原则、性能与复杂度权衡。
5. **决策内容 (Decision)**：具体做了什么设计？核心接口与职责划分。
6. **后果与影响 (Consequences)**：
   - *正向收益 (Positive)*：解决了什么问题？获得了什么能力？
   - *潜在成本/限制 (Negative / Trade-offs)*：付出了什么代价？有哪些注意事项？
