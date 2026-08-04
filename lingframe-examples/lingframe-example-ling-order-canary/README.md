# lingframe-example-ling-order-canary

> 迭代/多版本并存演示的**孤儿灵元**（有意不接入宿主）。

本模块与 [`lingframe-example-ling-order`](../lingframe-example-ling-order) 同源，仅包名不同（`canary` 副本），用于演示「同一契约多 provider 并存、N 元权重分流」的迭代版本形态。

> ⚠️**独立的示例演示单元**，用于展示灵元产物如何打包、如何声明 `Ling-Id`/`Ling-Version` manifest，以及如何与 `-canary` 副本构成多版本并存素材。它不参与可运行闭环，这与 `lingframe-example-ling-user-canary`（被 `lingcore-app` 托管）不同。

## 单独构建

```bash
mvn -pl lingframe-examples/lingframe-example-ling-order-canary -am package -DskipTests
```

产物 JAR 自带 `Ling-Id` 与 `Ling-Version` manifest，可放入灵核的 `ling-roots` 目录作为多版本并存的候选灵元。