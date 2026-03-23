# 快速开始

这份文档是第一次接触灵珑时的最短路径。

它刻意只解决一件事：**先把示例跑起来，并建立最小术语感**。

如果你只记住一句话，请记住：

> 灵珑让你在一个 JVM 进程里加载并治理彼此隔离的业务灵元，而不是一上来就把系统拆成微服务。

对 `0.3.0` 来说，这不只是“把灵元加载起来”的演示，  
也是你第一次接触一条可治理、可收敛、并且后续可继续验证规范热卸载的运行时链路。

---

## 你会跑起来什么

示例工程里，你会启动一个灵核应用，并让它加载两个示例灵元：

- `user-ling`
- `order-ling`

这一轮运行里，你会同时看到三件事：

- 灵元可以在同一进程内被加载
- 灵核可以通过共享契约调用灵元服务
- 调用过程仍然经过治理内核

---

## 环境要求

- JDK 17+ 作为主示例路径
- Maven 3.8+

`0.3.0` 同时支持 JDK 8 与 Spring Boot 2.x，但示例工程仍然是最容易上手的入口。

---

## 5 分钟跑通

### 1. 克隆仓库

```bash
# GitHub
git clone https://github.com/LingFrame/LingFrame.git

# AtomGit
git clone https://atomgit.com/lingframe/LingFrame.git

# Gitee
git clone https://gitee.com/LingFrame/LingFrame.git
```

### 2. 构建项目

```bash
cd LingFrame
mvn clean install -DskipTests
```

### 3. 启动示例灵核应用

```bash
cd lingframe-examples/lingframe-example-lingcore-app
mvn spring-boot:run
```

### 4. 验证示例是否正常

```bash
curl http://localhost:8888/user-ling/user/listUsers
curl "http://localhost:8888/user-ling/user/queryUser?userId=1"
```

如果这两个请求都能正常返回，你已经拥有一个可运行的灵珑运行时。

---

## 刚才启动了什么

### 灵核

`LingCore` 是当前进程里的灵核侧应用。它拥有运行时、治理内核，以及共享契约边界。

### 灵元

`Ling` 是在灵核进程里被独立加载的业务单元。

### Shared API

`Shared API` 是灵核与灵元之间、或者灵元与灵元之间的进程级公共契约层。跨边界使用的接口与 DTO 都应该放在这里。

作为新手，你先记住下面三句就够了：

- 灵核是当前进程里的灵核侧应用
- 灵元是隔离的业务单元
- Shared API 是双方共同遵守的契约

术语说明详见 [术语表](glossary.md)。

---

## 最小可用配置

示例应用已经带好了工作配置。最关键的是这些部分：

```yaml
server:
  port: 8888

lingframe:
  enabled: true
  dev-mode: true

  preload-api-jars:
    - lingframe-examples/lingframe-example-order-api

  ling-home: lings
  ling-roots:
    - lingframe-examples/lingframe-example-ling-order
    - lingframe-examples/lingframe-example-ling-user
```

这表达的含义是：

- 开启灵珑运行时
- 当前以开发友好的模式运行
- 在灵元启动前先 preload 共享契约
- 从本地示例源码路径发现灵元

---

## 这次跑通已经证明了什么

当示例成功跑起来时，你其实已经验证了四件事：

- 灵核可以在单进程里发现并加载灵元
- 共享契约会在灵元启动前先 preload
- 跨灵元调用不会绕过治理内核
- 当前示例配置已经足够继续阅读开发文档

下一步最值得继续验证的，不只是“还能不能再加载一个灵元”，  
而是这条运行时链路在 reload / unload / cleanup 场景下能否继续保持有序。

接下来如果你想先判断怎么落地，读 [实用入口](practical-entry.md)；如果你想直接开始写灵元，去 [业务灵元开发指南](ling-development.md)。
