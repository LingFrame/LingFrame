# LingFrame Quick Start

这份文档只做一件事：**用最短路径把 LingFrame 跑起来。**

如果你想先判断“能不能跑、界面能不能看到、请求能不能打通”，看这份就够了。  
如果你想正式入门，再去看 `docs/zh-CN/getting-started.md`。

## 1. 环境要求

- JDK 17+ 推荐
- Maven 3.8+

## 2. 构建示例

在仓库根目录执行：

```powershell
mvn -pl lingframe-examples/lingframe-example-lingcore-app -am package -DskipTests
```

## 3. 启动示例应用

```powershell
cd .\lingframe-examples\lingframe-example-lingcore-app
mvn spring-boot:run
```

默认入口：

- 应用与接口：`http://localhost:8888`
- Dashboard：`http://localhost:8888/dashboard.html`

## 4. 验证灵元是否加载

```powershell
curl http://localhost:8888/lingframe/dashboard/lings
```

正常情况下你会看到：

- `order-ling`
- `user-ling`

## 5. 发一个真实请求

```powershell
curl http://localhost:8888/user-ling/user/listUsers
```

如果返回正常，说明这条最小运行链已经打通：

- 灵核启动成功
- 灵元已加载
- Web 请求可达
- Dashboard 可观察

## 6. 可选：跑一条已验证回归

观测闭环回归：

```powershell
mvn -pl lingframe-examples/lingframe-example-lingcore-app -am -Pintegration-check verify "-Dit.test=ObservabilityClosedLoopIntegrationTest"
```

Dashboard 浏览器级冒烟：

```powershell
mvn -pl lingframe-examples/lingframe-example-lingcore-app -am -Pintegration-check verify "-Dit.test=DashboardUiSmokeIntegrationTest"
```

## 7. 下一步看什么

- 正式入门：`docs/zh-CN/getting-started.md`
- Dashboard 说明：`docs/zh-CN/dashboard.md`
- 项目总览：`README.zh-CN.md`
