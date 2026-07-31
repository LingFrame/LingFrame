# 故障排查手册

本文档帮助你快速定位和解决灵珑运行时常见问题。

---

## 快速诊断流程

```
问题发生
    │
    ├─→ 灵元无法加载？ ──────────────────→ 见 [ClassLoader 问题]
    │
    ├─→ 灵元无法启动？ ──────────────────→ 见 [生命周期问题]
    │
    ├─→ 调用失败或超时？ ────────────────→ 见 [调用链路问题]
    │
    ├─→ 内存持续增长？ ──────────────────→ 见 [内存泄漏问题]
    │
    ├─→ 状态异常？ ──────────────────────→ 见 [状态机问题]
    │
    └─→ 其他问题 ────────────────────────→ 见 [日志分析]
```

---

## 一、ClassLoader 问题

### 1.1 ClassNotFoundException / NoClassDefFoundError

**症状：**
```
java.lang.ClassNotFoundException: com.example.MyClass
java.lang.NoClassDefFoundError: com/example/MyClass
```

**可能原因：**

| 原因 | 排查方法 | 解决方案 |
|------|----------|----------|
| 类不在灵元 JAR 中 | 检查 JAR 包内容 | 确保类被打包 |
| 类在 Shared API 中但未注册 | 检查 `preload-api-jars` 配置 | 在配置中添加共享包 |
| 类被错误委派到父加载器 | 检查类名是否匹配委派规则 | 调整委派包配置 |
| SpringDoc 等反射库扫描冲突 | `NoClassDefFoundError` | 排除或延迟扫描，或将其设为 shared |
| 灵元依赖缺失 | 检查 `ling.yml` 中的 dependencies | 添加缺失依赖 |

**排查命令：**
```bash
# 查看 JAR 包内容
jar -tf your-ling.jar | grep MyClass

# 检查类加载路径
# 在日志中搜索
grep "ClassLoader" logs/lingframe.log
```

### 1.2 ClassCastException / LinkageError

**症状：**
```
java.lang.ClassCastException: com.example.MyClass cannot be cast to com.example.MyClass
java.lang.LinkageError: loader constraint violation
```

**原因：** 同一个类被不同 ClassLoader 加载了多次。

**排查步骤：**

1. 检查是否在 Shared API 和灵元中同时存在同一个类
2. 检查 Shared API 边界是否在灵元加载后才冻结
3. 检查是否有灵元热更新后未完全卸载

**解决方案：**
```yaml
# 确保 Shared API 在灵元加载前预加载并冻结
lingframe:
  preload-api-jars:
    - /path/to/shared-api.jar
  freeze-shared-api-before-ling-load: true
```

### 1.3 灵元卸载后文件被占用（Windows）

**症状：**
```
java.io.FileNotFoundException: The process cannot access the file because it is being used by another process
```

**原因：** Windows 平台上 JAR 文件句柄未释放。

**排查：**
```bash
# 使用 Process Explorer 查看文件句柄
# 或在日志中搜索
grep "close ClassLoader" logs/lingframe.log
```

**解决方案：**

1. 确保使用 JDK 8+，低版本 JDK 的 ClassLoader 关闭不完整
2. 检查灵元代码是否有静态变量持有 ClassLoader 引用
3. 启用泄漏检测：
```yaml
lingframe:
  dev-mode: true  # 开发模式下通过 DEV_AGGRESSIVE 进行激进泄漏诊断
```

### 1.4 第三方库反射扫描冲突 (SpringDoc / Swagger)

**症状：** 在 Spring Boot 3 配合 JDK 17 及以上版本使用 SpringDoc 时，可能会抛出 `NoClassDefFoundError` 或类加载死锁。

**原因：** SpringDoc 的 OpenAPI 扫描器会在容器启动或第一次请求时，通过反射深度遍历整个应用（甚至触及不同 ClassLoader 的类），如果灵元的内部类恰好暴露在控制器签名或属性中，扫描器会触发跨越 `LingClassLoader` 和 `AppClassLoader` 边界的连锁类加载，进而引发找不到类或链接错误。

**解决方案：**
1. **隐藏模型**：尽量不要在 Shared API 中暴露未定义的灵元私有模型到 Controller 签名。
2. **包过滤**：在 `application.yml` 中配置 SpringDoc 的 `packages-to-scan`，明确只扫描主程序的包，**排除灵元的包**：
```yaml
springdoc:
  packages-to-scan: com.my.core.app
```
3. **环境隔离**：将 SpringDoc 仅限在开发环境开启，生产环境禁用（推荐做法）。

---

## 二、生命周期问题

### 2.1 灵元卡在 LOADING 状态

**症状：** Dashboard 显示灵元状态为 LOADING，长时间不变。

**可能原因：**

| 原因 | 排查方法 |
|------|----------|
| 安全验证耗时 | 检查 `LingSecurityVerifier` 日志 |
| Spring Context 启动慢 | 检查灵元 Spring Bean 初始化日志 |
| 依赖服务不可用 | 检查灵元外部依赖连接状态 |

**排查日志：**
```
# 搜索生命周期事件
grep "LingLifecycleEngine\|InstanceStateChangedEvent" logs/lingframe.log

# 搜索卡住的阶段
grep "LOADING\|STARTING" logs/lingframe.log
```

### 2.2 灵元启动失败进入 ERROR 状态

**症状：**
```
Instance [my-ling] v1.0.0 state changed: STARTING -> ERROR
```

**排查步骤：**

1. 查看错误日志：
```bash
grep -A 20 "ERROR.*my-ling" logs/lingframe.log
```

2. 常见错误类型：

| 错误类型 | 典型日志 | 解决方案 |
|----------|----------|----------|
| Bean 创建失败 | `Error creating bean` | 检查 Spring 配置 |
| 依赖注入失败 | `No qualifying bean` | 检查 `@LingReference` 配置 |
| 权限不足 | `Permission denied` | 检查 `ling.yml` 中的 capabilities |
| 端口冲突 | `Port already in use` | 修改灵元端口配置 |

### 2.3 灵元无法卸载

**症状：** 卸载操作超时或卡住。

**排查：**
```bash
# 查看活跃请求数
grep "activeRequests" logs/lingframe.log

# 查看卸载进度
grep "STOPPING\|drain\|unload" logs/lingframe.log
```

**可能原因：**

1. **有请求未完成**：等待请求排空
2. **有后台线程未停止**：检查灵元是否创建了非守护线程
3. **资源未释放**：检查数据库连接、线程池等

**强制卸载（谨慎使用）：**
```yaml
lingframe:
  runtime:
    force-cleanup-delay-seconds: 30  # 超时后强制清理
```

---

## 三、调用链路问题

### 3.1 调用超时

**症状：**
```
LingInvocationException: Invocation timeout for service [my-ling:MyService.doSomething]
```

**排查步骤：**

1. 检查超时配置：
```yaml
# ling.yml 中
governance:
  timeout-ms: 5000
```

2. 检查目标灵元状态：
```bash
grep "RuntimeStatus\|InstanceStatus" logs/lingframe.log | grep my-ling
```

3. 检查是否有熔断：
```bash
grep "CircuitBreaker\|OPEN" logs/lingframe.log
```

### 3.2 调用被拒绝

**症状：**
```
LingInvocationException: Call rejected by governance
```

**可能原因：**

| 原因 | 日志关键词 | 解决方案 |
|------|------------|----------|
| 熔断器打开 | `CircuitBreaker OPEN` | 等待恢复或调整熔断阈值 |
| 限流触发 | `RateLimiter rejected` | 调整限流配置 |
| 权限不足 | `Permission denied` | 检查 capabilities 配置 |
| 宏观状态异常 | `RuntimeStatus=DEGRADED` | 检查灵元健康状态 |

### 3.3 灰度路由不生效

**症状：** 流量没有路由到灰度实例。

**排查：**

1. 检查权重路由配置——金丝雀已收敛为「按版本权重分流」，不再有 `governance.canary` yaml 配置。通过 Dashboard API 查询当前契约下 provider 权重：

```bash
curl http://localhost:8888/lingframe/dashboard/contract-routing/{contractId}
```

若需调整，下发运行期权重覆盖：

```bash
curl -X POST http://localhost:8888/lingframe/dashboard/contract-routing/{contractId}/weight \
  -H "Content-Type: application/json" \
  -d '{"providerKey": "user-ling:1.1.0", "weight": 30}'
```

2. 检查实例标签（金丝雀标记应放在 `labels` / `properties`，不再进路由决策链）：
```bash
grep "labels\|ProviderWeightRouter" logs/lingframe.log
```

3. 检查路由策略：
```bash
grep "ContractProviderRouting\|LabelMatchRouter\|ProviderWeightRouter" logs/lingframe.log
```

---

## 四、内存泄漏问题

### 4.1 堆内存持续增长

**症状：** JVM 堆内存使用量持续上升，Full GC 无法回收。

**排查步骤：**

1. 启用泄漏检测：
```yaml
lingframe:
  dev-mode: true  # 开发模式下启用激进诊断（DEV_AGGRESSIVE）与有界降级（DEV_BOUNDED）
  # 生产模式（dev-mode: false）自动回退到 PROD_PASSIVE 模式进行无损被动观测
```

2. 查看泄漏报告：
```bash
grep "LeakDetector\|memory leak" logs/lingframe.log
```

3. 生成堆转储分析：
```bash
# 触发堆转储
jmap -dump:format=b,file=heap.hprof <pid>

# 使用 MAT 或 VisualVM 分析
```

### 4.2 ClassLoader 泄漏

**症状：** 多次热更新后，Metaspace 持续增长。

**常见泄漏点：**

| 泄漏源 | 排查方法 | 解决方案 |
|--------|----------|----------|
| ThreadLocal 未清理 | 检查灵元中的 ThreadLocal | 在 `onStop()` 中清理 |
| 静态集合 | 检查灵元中的 static Map/List | 避免使用或主动清理 |
| 回调未注销 | 检查事件监听器注册 | 使用 EventBus 自动清理 |
| 线程池未关闭 | 检查灵元创建的线程池 | 在 `onStop()` 中关闭 |

**排查命令：**
```bash
# 查看 ClassLoader 数量
jcmd <pid> VM.classloaders

# 查看类统计
jcmd <pid> GC.class_stats | grep LingClassLoader
```

---

## 五、状态机问题

### 5.1 状态转换失败

**症状：**
```
IllegalStateTransitionException: Cannot transition from READY to LOADING
```

**原因：** 状态转换违反了状态机规则。

**状态转换规则：**

```
InstanceStatus:
  CREATED → LOADING → STARTING → READY → STOPPING → DEAD
      ↓         ↓          ↓        ↓         ↓
    ERROR ←───────────────────────────────────
    
RuntimeStatus:
  INACTIVE → ACTIVE ↔ DEGRADED
      ↓         ↓         ↓
  REMOVED ←  STOPPING ←──┘
```

**排查：**
```bash
grep "IllegalStateTransition\|StateMachine" logs/lingframe.log
```

### 5.2 RuntimeStatus 与 InstanceStatus 不一致

**症状：** 实例是 READY，但 Runtime 显示 DEGRADED。

**排查：**
```bash
# 查看状态聚合日志
grep "RuntimeCoordinator\|reevaluate" logs/lingframe.log
```

**可能原因：**

1. 有其他实例处于 ERROR 状态
2. 事件发布延迟
3. CAS 冲突导致状态未更新

---

## 六、日志分析

### 6.1 日志级别配置

```yaml
logging:
  level:
    com.lingframe: DEBUG
    com.lingframe.core.fsm: TRACE      # 状态机详细日志
    com.lingframe.core.pipeline: TRACE # Pipeline 详细日志
    com.lingframe.core.classloader: DEBUG  # ClassLoader 日志
```

### 6.2 关键日志关键词

| 场景 | 关键词 |
|------|--------|
| 生命周期 | `LingLifecycleEngine`, `InstanceStateChangedEvent` |
| 调用链路 | `InvocationPipelineEngine`, `Filter`, `invoke` |
| ClassLoader | `LingClassLoader`, `SharedApiClassLoader`, `loadClass` |
| 状态机 | `StateMachine`, `transition`, `CAS` |
| 治理 | `CircuitBreaker`, `RateLimiter`, `Permission` |
| 内存 | `LeakDetector`, `evict`, `cleanup` |

### 6.3 日志分析示例

```bash
# 查看某个灵元的完整生命周期
grep "my-ling" logs/lingframe.log | grep -E "Installing|Installed|Starting|READY|STOPPING|DEAD"

# 查看调用失败原因
grep -B 5 "LingInvocationException" logs/lingframe.log

# 查看状态转换链
grep "state changed" logs/lingframe.log | tail -50
```

---

## 七、Dashboard 诊断

### 7.1 通过 Dashboard 查看状态

1. **灵元列表**：查看所有灵元的 RuntimeStatus
2. **实例详情**：查看 InstanceStatus、活跃请求数
3. **治理面板**：查看熔断器状态、限流器状态
4. **事件流**：SSE 实时查看运行时事件

### 7.2 模拟调用

使用 Dashboard 的模拟功能，可以在不产生真实副作用的情况下测试治理链路：

```
POST /lingframe/dashboard/simulate/lings/{lingId}/ipc
{
  "serviceId": "MyService.doSomething",
  "args": ["param1", "param2"]
}
```

详见 [Dashboard 文档](dashboard.md)。

---

## 八、常见错误代码

| 错误代码 | 含义 | 解决方案 |
|----------|------|----------|
| `CLASS_LOADER_CLOSED` | ClassLoader 已关闭 | 检查灵元是否已卸载 |
| `INSTANCE_NOT_READY` | 实例未就绪 | 等待实例启动完成 |
| `PERMISSION_DENIED` | 权限不足 | 检查 capabilities 配置 |
| `CIRCUIT_BREAKER_OPEN` | 熔断器打开 | 等待恢复或调整阈值 |
| `RATE_LIMITED` | 限流触发 | 调整限流配置或降低请求频率 |
| `TIMEOUT` | 调用超时 | 增加超时时间或优化性能 |
| `STATE_CONFLICT` | 状态冲突 | 重试操作 |

---

## 九、获取帮助

如果以上方法无法解决问题：

1. **提交 Issue**：[GitHub Issues](https://gitee.com/LingFrame/LingFrame/issues)
2. **提供信息**：
   - 灵珑版本
   - JDK 版本
   - Spring Boot 版本
   - 完整错误日志
   - 复现步骤
