# 文档怎么读

按**你现在想干什么**找，不用从上到下读完。  
更短的“要不要用 + 先跑起来”在仓库根目录 [README.md](../../README.md)。

> `docs/development/` 是内部材料，不是给用户看的现行说明。

---

## 建议顺序

1. [根 README](../../README.md) — 适不适合我、大概多快、怎么跑  
2. [最短上手](../../QUICK_START.md) — 命令细节  
3. [示例说明](../../lingframe-examples/README.md) — 入门线 / 商城演进线  
4. 真要接到自己项目时，再看下面“接入与开发”

---

## 判断要不要用

| 文档 | 干什么用 |
| --- | --- |
| [根 README](../../README.md) | 问题、场景、和常见做法的差别、边界 |
| [快速开始](getting-started.md) | 首轮接入策略 + 从零构建完整应用 |
| [术语表 & FAQ](faq.md) | 名词解释 + 常见问题 |
| [WHY](../../WHY.md) / [MANIFESTO](../../MANIFESTO.md) | 设计立场（可选） |

---

## 跑起来、看现象

| 文档 | 干什么用 |
| --- | --- |
| [最短上手](../../QUICK_START.md) | 启动命令 |
| [快速开始](getting-started.md) | 刚才启动了什么、说明了什么 |
| [控制台说明](dashboard.md) | 控制台能做什么 |
| [示例说明](../../lingframe-examples/zh-CN/README.md) | 两条示例线 |
| [可观测性](observability.md) | 事件、指标怎么看 |

跑通后建议：先看灵元列表 → 打一笔真实请求 → 再扫监控/治理。

---

## 接入与开发

| 文档 | 干什么用 |
| --- | --- |
| [业务灵元开发](ling-development.md) | 怎么写一个灵元 |
| [Shared API 规范](shared-api-guidelines.md) | 共用接口契约怎么放、怎么演进 |
| [基础设施开发](infrastructure-development.md) | 数据库 / 缓存等代理路径 |
| [saas-mall 说明](../../lingframe-examples/lingframe-example-saas-mall/README.md) | 商城演进对照 |
| [生产配置清单](production-hardening.md) | 上线前配置 |

---

## 出问题、要上生产

| 文档 | 干什么用 |
| --- | --- |
| [生产配置清单](production-hardening.md) | 令牌、开发模式、卸载超时等 |
| [故障排查](troubleshooting.md) | 加载失败、调用超时、内存等 |
| [术语表 & FAQ](faq.md) | 常见问题 |

---

## 架构与参与开发

| 文档 | 干什么用 |
| --- | --- |
| [架构设计](architecture.md) | 整体怎么拼起来的（含双层状态机） |
| [开发手册](../../DEVELOPMENT_MANUAL.md) | 贡献与开发规则（含双栈第 5.2 节） |
| [贡献指南](../../CONTRIBUTING.md) | 怎么提 PR |

---

## 版本

| 文档 | 干什么用 |
| --- | --- |
| [更新日志](../../CHANGELOG.md) | 每版交付明细 |
| [路线图](roadmap.md) | 走到哪了（明细以更新日志为准） |

---

## 性能测试

| 文档 | 干什么用 |
| --- | --- |
| [基准说明](../../lingframe-benchmark/README.md) | 怎么跑 |
| [样例跑分](../../lingframe-benchmark/benchmark-results-20260709-044113.txt) | 一次原始结果 |

---

English: [docs/zh-CN/README.md](../../README.en.md)
