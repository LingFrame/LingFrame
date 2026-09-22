# 灵珑（LingFrame）万级 QPS 并发压测基建

本目录提供可复现的压测脚本，用于量化灵核治理链在高并发下的吞吐与延迟特征。
压测目标为示例灵核应用（`lingframe-example-lingcore-app`，默认 `http://localhost:8888`）。

## 压测目标端点

| 端点 | 方法 | 说明 | 治理链 |
| --- | --- | --- | --- |
| `/lingcore/hello` | GET | 问候接口（最简路径） | Web 治理链（GOVERN_ONLY） |
| `/lingcore/config/{key}` | GET | 读配置（带 PathVariable） | Web 治理链（GOVERN_ONLY） |
| `/lingcore/configs` | GET | 列全部配置 | Web 治理链（GOVERN_ONLY） |

> 说明：灵元服务调用的完整 NORMAL 治理链（12 个内置 Filter + 事务穿透）延迟基线由
> `lingframe-benchmark` 模块的 JMH 基准覆盖（`PipelineBenchmark` / `TransactionPropagationBenchmark`）；
> 本目录 HTTP 压测聚焦「进程入口 + Web 治理链」的端到端吞吐与并发抖动。

## 前置条件

1. 构建并启动示例灵核应用：

```bash
mvn -pl lingframe-examples/lingframe-example-lingcore-app -am package -DskipTests
cd lingframe-examples/lingframe-example-lingcore-app && mvn spring-boot:run
# 默认 http://localhost:8888 ，Dashboard: /dashboard.html
```

2. 健康检查：`curl http://localhost:8888/lingcore/hello` 应返回正常响应。

3. 压测工具（二选一）：
   - **wrk**（Linux / macOS）：`apt install wrk` 或 `brew install wrk`
   - **JMeter**（跨平台，含 Windows）：官网下载二进制，或 `brew install jmeter`

## wrk 用法（推荐，Linux/macOS 万级 QPS）

```bash
# 10 万请求、100 并发、持续 30s，压 /lingcore/hello
wrk -t8 -c100 -d30s --latency -s scripts/loadtest/wrk/hello.lua http://localhost:8888

# 万级 QPS 脚本（自动控制时长，见脚本内注释）
bash scripts/loadtest/wrk/run-wrk-10k.sh
```

wrk 输出解读：
- `Requests/sec`：聚合吞吐（目标：万级 QPS 下应稳定在数千至数万，取决于机器）
- `Latency` / `p99`：延迟分布（目标：p99 无显著长尾，热更新期间允许短暂抖动）
- `Socket errors`：出现大量错误说明连接池/治理链存在瓶颈

## JMeter 用法（跨平台，含 Windows）

```bash
# 打开 GUI 查看计划
jmeter -t scripts/loadtest/jmeter/lingframe-hello.jmx

# 无头模式运行（结果写入 CSV）
jmeter -n -t scripts/loadtest/jmeter/lingframe-hello.jmx -l scripts/loadtest/jmeter/results.csv -j /tmp/jmeter.log
```

计划内容（`lingframe-hello.jmx`）：
- 线程组：`100` 线程，`30` 秒持续（Loop 无限 + 持续时长控制），`10` 秒启动斜坡
- HTTP 采样：GET `/lingcore/hello`（可改 host/port）
- 聚合报告 + 查看结果树监听器

## 并发热更新抖动观测（Benchmark 模块）

高并发流量中反复热更新/热卸载的吞吐抖动与锁竞争观测，见：

```bash
# 热更新抖动基准（JMH）
mvn -pl lingframe-benchmark package -Pbenchmark -am -DskipTests
java -jar lingframe-benchmark/target/lingframe-benchmarks.jar HotUpdateJitterBenchmark -f 1 -t 8
```

该基准在 8 线程并发调用流中周期性执行 `deployLing` / `undeployLing`，
对比「无热更新基线」与「热更新进行中」的吞吐与延迟，观测线程池抖动与锁竞争。

## 注意事项

1. **Dev 模式**：开发配置 `lingframe.dev-mode: true` 会放宽权限校验，压测结果不代表生产；
   生产压测建议关闭 dev-mode 并按 `docs/zh-CN/production-hardening.md` 配置。
2. **Dashboard 鉴权**：压测路径不含 Dashboard，不受 token 影响；若压测包含 Dashboard 端点需带 token。
3. **机器差异**：万级 QPS 目标受 CPU/内存/JVM 堆影响显著，先跑小并发校准机器基线，
   再逐步放大并发，避免把 GC 停顿误判为框架开销。
4. **热更新压测**：`HotUpdateJitterBenchmark` 的 deploy/undeploy 会触发 ClassLoader 创建与卸载，
   需保证 `-Xmx` 充足（建议 2g+），避免频繁 Full GC 干扰观测。
