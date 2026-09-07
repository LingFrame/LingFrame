# ADR-0004: 动态类加载防御性排空（Drain）、濒死队列与弱引用泄漏检测机制

- **状态**: Accepted
- **决策日期**: 2026-08-20

---

## 1. 背景与问题 (Context)

在 JVM 环境下实现动态插件化与频繁热重载时，最大的技术梦魇是 **Metaspace / 堆内存泄漏**。如果旧版本的 `ClassLoader` 因为以下原因未能被垃圾回收器（GC）回收：
- 线程池在途请求未执行完毕；
- Spring 容器中的静态变量或单例持有了插件类；
- `ThreadLocal` 未显式清理；

反复部署与热重载就会迅速导致 JVM `OutOfMemoryError: Metaspace` 崩溃。

---

## 2. 决策驱动因素 (Decision Drivers)

- **第一性原理与生命周期物理现实**：不能假设“只要调用了 `close()` 类加载器就会立刻被释放”，必须从时序、引用链路和状态机多重维度建立闭环。
- **宁可牺牲瞬时速度，不可造成雪崩崩溃**：优雅排空（Drain）比粗暴杀死线程更可靠。

---

## 3. 决策内容 (Decision)

1. **引入“濒死队列（DyingQueue）”与视图隔离**：
   - 实例一旦触发替换或卸载，第一步即从 `activePool` 移除并压入 `dyingQueue`，状态转为 `STOPPING`；
   - 外部查询视图（如控制台、路由发现）立刻看不到旧版本，杜绝热重载期间出现“版本分裂”假象。

2. **多阶段排空窗口（Drain Instances）**：
   - 轮询等待活跃调用计数归零（`hasActiveInvocations == false`）；
   - 提供 `forceCleanupDelaySeconds` 超时保底与线程中断机制。

3. **完整卸载顺序与资源销毁**：
   - `Container.stop` $\rightarrow$ `Container.destroy` $\rightarrow$ `ClassLoader.close()` $\rightarrow$ 服务注册表反注册；
   - 彻底防止类加载器悬空。

4. **弱引用泄漏探测（LeakDetector）**：
   - 通过 `WeakReference` 跟踪已卸载实例与类加载器；
   - 在后台触发弱引用队列探测并输出 `LeakRiskReport`，使内存泄漏具备即时可观测性。

---

## 4. 后果与影响 (Consequences)

### 正向收益
- **支持高频热重载**：在自动化测试与生产热更新中，类加载器能够被 GC 干净回收；
- **排查透明度**：一旦发生第三方库静态泄漏，`LeakDetector` 能精准定位泄漏的类与风险等级。
