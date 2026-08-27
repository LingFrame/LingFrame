# lingframe-example-ling-native

> 原生（非 Spring）灵元加载演示的**孤儿灵元**（有意不接入灵核）。

本模块演示灵珑对**非 Spring Boot 原生灵元**的加载能力：`NativeLing` 不是 `@SpringBootApplication`，而是通过原生容器（`lingframe-runtime` 的 native adapter）被加载的轻量灵元实现。

> ⚠️**独立的示例演示单元**，用于展示灵珑如何加载纯 Java / 非 Spring 生态的灵元产物（native adapter 路径）。它不参与可运行闭环，与基于 Spring 的 `lingframe-example-ling-user` 系列不同。

## 单独构建

```bash
mvn -pl lingframe-examples/lingframe-example-ling-native -am package -DskipTests
```

产物 JAR 打包后可作为原生灵元候选，放入灵核的 `ling-roots` 目录验证 native 加载路径。