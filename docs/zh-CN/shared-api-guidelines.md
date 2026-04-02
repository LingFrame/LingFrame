# Shared API 设计规范

这份文档对第一次接触灵珑的开发者非常重要。

> `Shared API` 是灵核与灵元之间、以及灵元之间的进程级公共契约边界。

---

## 什么应该放进 Shared API

`Shared API` 应该只放契约相关内容：

- 接口
- DTO
- 少量契约级别的枚举和值对象

下面这些不应该放进去：

- 业务实现
- repository
- Spring 组件
- 某一侧私有实现用到的持久化实体

---

## 消费者驱动契约规则

灵珑使用“消费者驱动”模式：

- 由消费者定义它需要的接口
- 由生产者实现这份接口

```java
public interface UserQueryService {
    Optional<UserDTO> findById(String userId);
}
```

```java
@Component
public class UserQueryServiceImpl implements UserQueryService {
    @LingService(id = "find_user")
    @Override
    public Optional<UserDTO> findById(String userId) {
        return userRepository.findById(userId).map(this::toDTO);
    }
}
```

---

## `0.3.0` 里的 classloader 现实

当前运行时可以大体理解成三层关系：

- 灵核 classloader
- `SharedApiClassLoader`
- 灵元实现 classloader

这意味着：

- 契约类必须通过 shared 层可见
- 实现类应留在灵元层
- 同一份契约类不能在多个地方重复打包加载

---

## 为什么这样设计——规避依赖地狱

传统模块化框架（以 OSGi 为代表）把依赖解析推到运行时：模块 A 启动时去找模块 B，找不到就挂起或失败，部署顺序、版本冲突、循环依赖全部变成运维负担。这是模块化系统最令人头疼的地方。

灵珑的选择是彻底切断这条路：

- **契约在进程启动时固定**（preload → freeze），之后所有灵元看到的是同一份不变的接口
- **消费者不需要等生产者就绪**：`order-ling` 拿到 `UserQueryService` 接口的引用，无论 `user-ling` 有没有加载，调用时才决定是否可用
- **生产者没来就快速失败**：调用时没有实现 → 抛 `LingNotFoundException`，而不是启动时挂起等待
- **生产者来了就透明接入**：`user-ling` 热部署后，`SmartServiceProxy` 自动路由到新实现，消费者无感知

结果是：灵元之间没有启动顺序依赖，没有版本协商，没有循环依赖问题。每个灵元都是真正可以独立热插拔的执行单元。

代价是诚实且明确的：**已加载的共享契约不能热更新**。契约变更需要重启进程。这个限制换来了整个依赖体系的简单性。

---

## 必须尊重的启动边界

在 `0.3.0` 里，`Shared API` 的启动顺序已经是显式规则：

1. preload 共享 JAR 或 classes 目录
2. 注册共享包前缀
3. freeze 共享边界
4. 然后再加载灵元

### 这意味着什么

- 全新的共享 jar 可以在 freeze 前引入
- 已经加载过的共享契约不能原地热更新
- 变更既有共享契约仍然需要重启进程

---

## DTO 设计规则

好的 DTO 往往“刻意普通”。

```java
@Data
public class OrderDTO implements Serializable {
    private Long id;
    private String orderNo;
    private BigDecimal amount;
}
```

避免在 DTO 中写业务行为，或直接嵌入某个灵元的私有实体模型。

---

## 演进规则

安全变更：

- 新增方法
- 新增可选字段
- 通过新包名承载破坏性变更

高风险变更：

- 原地修改已有方法签名
- 不兼容变更还继续复用原包名
- 误以为共享契约可以安全热更新

---

## `preload-api-jars` 常见配置示例

```yaml
lingframe:
  preload-api-jars:
    - api/order-api-*.jar
    - api/user-api/
    - lingframe-examples/lingframe-example-order-api
```

---

## 常见错误

### `ClassNotFoundException`

通常意味着共享契约没有被正确 preload。

### `ClassCastException`

通常意味着同一个类被多个 classloader 视图加载了。

如果你接下来要按这套契约边界去写灵元，继续读 [业务灵元开发指南](ling-development.md)。
