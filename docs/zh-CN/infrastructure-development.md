# 基础设施开发指南

这份指南从 `0.3.0` 的真实实现出发，解释基础设施层该怎么理解。

> 基础设施模块不是普通业务灵元，而是围绕共享能力做治理感知代理的路径，例如存储和缓存。

---

## 为什么需要基础设施层

基础设施层存在的意义，是让运行时能够：

- 集中封装能力入口
- 在接近真实操作的位置做权限控制
- 产出审计证据
- 让业务代码尽量感知不到代理细节

---

## 当前已经实现了什么

在公开 `0.3.0` 代码里，最清晰的实现路径是：

- `lingframe-infra-storage`
- `lingframe-infra-cache`

---

## 存储代理路径

存储模块通过包装 JDBC 路径，让 SQL 操作在靠近执行点的位置被观测和治理。

关键组成包括：

- `DataSourceWrapperProcessor`
- `LingDataSourceProxy`
- `LingConnectionProxy`
- `LingStatementProxy`
- `LingPreparedStatementProxy`

典型 capability：

- `storage:sql`

---

## 缓存代理路径

缓存模块负责治理本地缓存与 Redis 相关访问路径。

关键组成包括：

- `SpringCacheWrapperProcessor`
- `LingCacheManagerProxy`
- `LingSpringCacheProxy`
- `RedisPermissionInterceptor`

典型 capability：

- `cache:local`
- `cache:redis`

---

## 如何理解 capability

Capability 标识应该：

- 稳定
- 明确
- 尽量靠近真实底层能力

当前代码里常见的标识有：

- `storage:sql`
- `cache:local`
- `cache:redis`

---

## 什么时候值得新建一个基础设施代理

当下面条件成立时，才值得新增一条代理路径：

- 能力被多个灵元共享
- 这个能力需要被一致治理
- 权限和审计应该贴近真实操作发生点

---

## 最小扩展模式

大多数基础设施扩展都遵循同一个结构：

1. 包装或拦截底层能力入口
2. 推断 `READ` / `WRITE` / `EXECUTE` 等访问类型
3. 调用权限服务
4. 产出审计证据
5. 继续执行或直接拒绝

---

## 最佳实践

- 让代理对业务使用者保持透明
- 拦截点尽量靠近真实操作
- capability 命名保持一致
- 有异步审计路径时，不要让审计阻塞主业务流
- 明确区分“已经实现的路径”和“未来想做的方向”

如果你要回到业务灵元侧，读 [业务灵元开发指南](ling-development.md)；如果你要看契约边界，读 [Shared API 设计规范](shared-api-guidelines.md)。
