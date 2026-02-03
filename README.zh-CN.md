# LingFrame · 灵珑

![Status](https://img.shields.io/badge/Status-Core_Implemented-green)
![License](https://img.shields.io/badge/License-Apache_2.0-blue)
![Java](https://img.shields.io/badge/Java-21-orange)
![Spring Boot](https://img.shields.io/badge/Spring_Boot-3.5.6-brightgreen)
[![Help Wanted](https://img.shields.io/badge/PRs-welcome-brightgreen)](../../pulls)
[![Ask DeepWiki](https://deepwiki.com/badge.svg)](https://deepwiki.com/LingFrame/LingFrame)

[English Version](./README.md)

## 你可以从这里开始

- **技术入口**：深入治理细节与架构 👉 [technical-entry.md](docs/zh-CN/technical-entry.md)
- **实用入口**：快速上手与灰度发布 👉 [practical-entry.md](docs/zh-CN/practical-entry.md)
- **快速试用**：👉 [getting-started.md](docs/zh-CN/getting-started.md)
- **核心立场**：👉 [MANIFESTO.md](MANIFESTO.md)
- **设计原则与边界选择**：👉 [WHY.md](WHY.md)

你不需要一次性读完所有内容。  
灵珑允许你在任何阶段停下。

---

![LingFrame Dashboard 示例](./docs/images/dashboard.zh-CN.png)

---

LingFrame（灵珑）是一个**面向长期运行系统的 JVM 运行时治理框架**。  
它尝试在**不重写系统、不强行拆分微服务**的前提下，让已经服役多年的单体应用，继续稳定、可控、可演进地运行下去。

很多系统并不是设计得不好，  
只是活得太久，改得太急。

---

## 序章

它最初并不是为了优雅而诞生的。

只是某一天，人们发现系统已经大到无法理解，却又不能停下。  
每一次改动都像在黑夜中摸索，  
每一次上线都伴随着祈祷。

于是，有人开始问一个看似保守的问题：

> 如果系统暂时无法被重写，  
> 那它是否还能被**治理**？

不是通过更多规则，  
而是通过**更清晰的边界**。  
不是替系统做决定，  
而是让系统在还能被理解的时候，  
把事情放回该在的位置上。

灵珑由此诞生。

---

## 灵珑关心的，并不是“加功能”

在大量真实系统中，问题往往不是功能不足，而是：

- 系统仍在运行，但已经没人敢改  
- 模块边界逐渐失效，耦合无法追溯  
- 插件化引入后，隔离却只停留在结构层  
- 重启不是不能接受，而是**无法预期**

灵珑关注的核心问题只有一个：

> **系统在长期运行中，如何不失控。**

---

## 当前阶段

**v0.1.x · 少女期（预览版）**

这是一个方向已经冻结、边界正在成型的阶段：

- 不追求功能完整
- 不承诺向后兼容
- 只验证一件事：  
  **运行时治理在单进程内是否成立**

这是一个拒绝讨好、开始选择的阶段。

---

## 灵珑是什么

- 一个 **JVM 运行时治理框架**
- 一个 **面向老系统的结构性工具**
- 一个 **允许插件存在，但不纵容插件失控的体系**

它不是微服务替代品，  
也不是模块化银弹。

灵珑存在的意义，是在系统复杂到某个阶段时，  
**为“回缩”与“重组”提供可能性**。

---

## 技术边界（简述）

- JVM：JDK 21 / JDK 8（后续兼容支持）
- Spring Boot：3.x / 2.x（后续兼容支持）
- 单进程内插件隔离与治理
- 明确区分：**接口稳定性 ≠ 实现稳定性**

灵珑不隐藏复杂性，  
只是拒绝把复杂性一次性压给使用者。

---

## 最后

灵珑不会替系统做决定。

她只是在系统还愿意被理解的时候，  
帮你把事情放回该在的位置上。

如果你只是走到这里停下，  
那也完全没有关系。
