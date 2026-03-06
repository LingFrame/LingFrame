# 贡献指南

感谢你对 LingFrame 的关注！

## 快速开始

### 环境要求

- JDK 17+
- Maven 3.8+
- IDE：IntelliJ IDEA（推荐）

### 构建项目

```bash
# 克隆仓库（选择任意仓库）
# AtomGit（推荐）
git clone https://atomgit.com/lingframe/LingFrame.git

# Gitee（国内镜像）
git clone https://gitee.com/knight6236/lingframe.git

# GitHub（国际）
git clone https://github.com/LingFrame/LingFrame.git

cd LingFrame
mvn clean install -DskipTests
```

### 运行示例

```bash
cd lingframe-examples/lingframe-example-lingcore-app
mvn spring-boot:run
```

---

## 架构必读

> ⚠️ **请务必理解这些原则，否则 PR 可能被拒绝**

### 核心原则

| 原则 | 说明 |
|------|------|
| **零信任** | 业务灵元不能直接访问 DB/Redis，必须经过 Core 代理 |
| **微内核** | Core 只做调度仲裁，不包含业务逻辑 |
| **契约优先** | 所有交互通过 `lingframe-api` 接口 |
| **生态无关** | Core 是纯 Java，不依赖 Spring/ORM |

### 灵元职责

| 灵元 | 职责 | 依赖规则 |
|------|------|----------|
| `lingframe-api` | 契约层 | 无外部依赖 |
| `lingframe-core` | 治理内核 | **禁止**依赖 Spring |
| `lingframe-runtime` | 生态适配 | 桥接 Core 和 Spring |
| `lingframe-dashboard` | 可视化 | 依赖 Spring Web |

### 设计原则

- **SRP**：每个类只做一件事
- **DIP**：依赖抽象，不依赖具体实现
- **OCP**：通过扩展点增加功能

---

## 从哪里开始？

### 新手友好任务

在 [Issues](../../issues) 中查找以下标签：

| 标签 | 适合人群 |
|------|----------|
| `good first issue` | 第一次贡献 |
| `help wanted` | 需要帮助 |
| `documentation` | 文档改进 |

### 当前需要帮助的方向

- ⏳ 灵元测试补充
- ⏳ 消息代理（Kafka/RabbitMQ）
- ⏳ 文档完善

---

## 贡献流程

### 1. 认领任务

在 Issue 下留言："我想认领这个任务"

### 2. 开发

```bash
# Fork 后克隆
git clone https://github.com/YOUR_USERNAME/lingframe.git

# 创建分支
git checkout -b feature/your-feature

# 开发并提交
git commit -m "feat: add your feature"
git push origin feature/your-feature
```

### 3. 提交 PR

- [ ] 代码编译通过：`mvn clean compile`
- [ ] 测试通过：`mvn test`
- [ ] 描述清楚改动内容

---

## 代码规范

### 命名约定

| 类型 | 规则 | 示例 |
|------|------|------|
| 接口 | 描述性名称 | `LingContext` |
| 实现类 | `Default` 前缀 | `DefaultPermissionService` |
| 代理类 | `Proxy` 后缀 | `SmartServiceProxy` |
| 工厂类 | `Factory` 后缀 | `SpringContainerFactory` |

### 代码风格

- 4 空格缩进
- 类和方法添加 Javadoc
- 使用 Lombok 减少样板代码
- 使用 SLF4J 日志

### 测试要求

- 核心逻辑必须有灵元测试
- 测试类命名：`XxxTest.java`
- 使用 JUnit 5 + Mockito

### 提交信息

使用 [Conventional Commits](https://www.conventionalcommits.org/)：

```
feat: add permission check for SQL execution
fix: fix classloader memory leak on ling unload
docs: update quick start guide
```

---

## 问题反馈

| 类型 | 渠道 |
|------|------|
| Bug | [Issues](../../issues) |
| 功能建议 | [Discussions](../../discussions) |
| 安全问题 | 私信维护者 |

---

## 行为准则

- 尊重每一位贡献者
- 保持友善和专业
- 接受建设性的批评

感谢你的贡献！🎉
