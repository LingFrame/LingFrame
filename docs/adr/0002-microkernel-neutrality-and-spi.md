# ADR-0002: 保持微内核纯粹性与 SPI 容器扩展（拒绝内核强绑定多容器）

- **状态**: Accepted
- **决策日期**: 2026-08-20

---

## 1. 背景与问题 (Context)

在评估是否要将复杂的应用层容器（如针对 Java/Groovy/Spring 的混合双容器支持）内化到 LingFrame 内核时，曾出现两种声音：
- **方案 A（内置大包围）**：在 LingFrame 核心直接预置并管理双容器（如轻量级 Spring + Native 沙箱），使所有接入方开箱即具备双容器能力。
- **方案 B（微内核 + SPI 按需扩展）**：LingFrame 仅定义 `LingContainer` / `ContainerFactory` SPI 契约，内核保持轻量中立，具体容器实现按需插拔。

---

## 2. 决策驱动因素 (Decision Drivers)

- **第一性原理与资源占用**：并非所有场景都需要双容器。在单容器、轻量计算或仅需原生类加载的场景下，强行预置双容器会造成不可接受的内存占用、类加载负担以及生命周期管理复杂度。
- **YAGNI (You Aren't Gonna Need It)**：不要为极低概率或特定领域的诉求在框架内核增加常态化负担。
- **开闭原则 (OCP)**：对容器类型扩展开放（通过 SPI），对内核编排逻辑修改关闭。

---

## 3. 决策内容 (Decision)

1. **坚持微内核架构**：
   - LingFrame 内核只认识 `LingContainer` 抽象接口与生命周期钩子（`start`、`stop`、`isActive`、`getClassLoader`）；
   - 内核不预设、不强制、不绑定任何特定的 IoC 框架或多容器组合。

2. **多容器支持走领域扩展/SPI**：
   - 需要双容器能力的场景（如 LingAI 复杂的智能体沙箱），由外部实现 `ContainerFactory` 或由上层进行组合包装；
   - 内核只保证在任意 `LingContainer` 注入时提供统一的生命周期编排与排空治理。

---

## 4. 后果与影响 (Consequences)

### 正向收益
- **内核极致轻量**：单容器与纯 Native 场景无任何多余开销；
- **生态中立**：支持 Spring 2.x、Spring 3.x、SOFA、Native、Groovy 等多种生态无缝接入；
- **演进解耦**：容器实现的升级迭代不会破坏内核状态机。

### 限制与注意事项
- 上层若需要特殊的多容器能力，需要自行提供或依赖专门的容器适配 starter。
