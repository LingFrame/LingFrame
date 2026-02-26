# LingFrame · 灵珑

![Status](https://img.shields.io/badge/Status-Resilience_Governance-brightgreen)
![License](https://img.shields.io/badge/License-Apache_2.0-blue)
![Java](https://img.shields.io/badge/Java-17-orange)
![Java](https://img.shields.io/badge/Java-8-orange)
![Spring Boot](https://img.shields.io/badge/Spring_Boot-3.5.6-brightgreen)
![Spring Boot](https://img.shields.io/badge/Spring_Boot-2.7.18-brightgreen)

[![Gitee](https://img.shields.io/badge/Gitee-Repository-red?logo=gitee&logoColor=white)](https://gitee.com/knight6236/lingframe)
[![AtomGit G-Star](https://img.shields.io/badge/AtomGit-G--Star_孵化项目-silver?logo=git&logoColor=white)](https://atomgit.com/lingframe/LingFrame)
[![GitHub](https://img.shields.io/badge/GitHub-Repository-black?logo=github&logoColor=white)](https://github.com/LingFrame/LingFrame)

[![Help Wanted](https://img.shields.io/badge/PRs-welcome-brightgreen)](../../pulls)
[![Ask DeepWiki](https://deepwiki.com/badge.svg)](https://deepwiki.com/LingFrame/LingFrame)

[![Open in GitHub Codespaces](https://github.com/codespaces/badge.svg)](https://codespaces.new/LingFrame/LingFrame?quickstart=1)

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
- 单元边界逐渐失效，耦合无法追溯  
- 单元化引入后，隔离却只停留在结构层  
- 重启不是不能接受，而是**无法预期**

灵珑关注的核心问题只有一个：

> **系统在长期运行中，如何不失控。**

---

## 当前阶段

**v0.2.0 · 蜕变**

这是一个打破桎梏、重定义边界的阶段：

- 抛弃 "Plugin" 的认知局限，确立真正的 "Ling（单元）" 隔离
- 弹性治理不再流于控制面，化作宿主内核真正的熔断器
- 直面类加载器泄漏的深水区，尽可能解决卸载问题
- 证明另一件事：  
  **单体架构下的热拔插是否能真正承载工业级的高可用挑战**

这是一个褪去青涩、准备直面残酷生产环境的阶段。

---

**v0.1.0 · 初啼（历史版本）**

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
- 一个 **允许单元存在，但不纵容单元失控的体系**
- 一个 **拥有滑动窗口熔断、限流支持及 SPI 生态连接能力的高可用底座**

它不是微服务替代品，  
也不是单元化银弹。

灵珑存在的意义，是在系统复杂到某个阶段时，  
**为“回缩”与“重组”提供可能性**。

---

## 技术边界（简述）

- JVM：JDK 17 / JDK 8
- Spring Boot：3.x / 2.x
- 单进程内单元隔离与治理
- **可用性与生态**：原生配备灰度发布、熔断限流，并通过外骨骼扩展无缝对接 Nacos / Apollo 等第三方基础设施。
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

---

## 致谢

**特别鸣谢 Gitee 官方与开源社区的推荐与支持！** 

感谢 [Gitee](https://gitee.com) 平台以及红薯老师为本土开源生态提供的优质土壤，让底层的轮子也能被看见。
👉 [访问 Gitee 官方主仓库](https://gitee.com/knight6236/lingframe)

---

[![AtomGit](docs/images/AtomGit.svg)](https://atomgit.com/lingframe/LingFrame)

本项目也是 AtomGit G-Star 孵化项目。  
感谢 [AtomGit](https://atomgit.com) 平台对开源项目的支持与推广。


