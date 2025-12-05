# LingFrame API 设计总结

本文档总结了 LingFrame 框架 `lingframe-api` 模块的设计理念、核心接口及其在整体框架中的作用，并结合了关于权限管理和扩展点设计的深入讨论。

## 1. `lingframe-api` 模块定位

`lingframe-api` 模块是 LingFrame 框架的**核心契约层**，它定义了框架与插件之间交互的标准化接口和抽象。其核心原则是**保持纯净和稳定**，不包含具体的业务逻辑或基础设施实现，只提供框架运行和管理插件所必需的、高度抽象的接口。

## 2. 核心接口与契约

### 2.1 插件生命周期 (`com.lingframe.api.plugin`)

*   **`LingPlugin`**: 插件的主入口接口，定义了 `onStart` 和 `onStop` 方法。所有插件的主类必须实现此接口，并通过 `PluginContext` 与框架环境交互，而非直接获取静态资源。

### 2.2 上下文与仲裁 (`com.lingframe.api.context`)

*   **`PluginContext`**: 插件与 Core 交互的**唯一桥梁**。它体现了“零信任”原则，插件必须通过此上下文向 Core 申请基础设施能力、获取配置、发布事件，以及获取权限服务。
    *   `getPluginId()`: 获取当前插件的唯一标识。
    *   `getProperty(String key)`: 获取受控的配置信息。
    *   `getService(Class<T> serviceClass)`: 获取系统服务或能力，Core 负责仲裁和管理服务实例。
    *   `getPermissionService()`: 获取 Core 提供的权限服务，用于权限查询和审计。
    *   `publishEvent(LingEvent event)`: 支持插件间解耦通信。

### 2.3 扩展机制 (`com.lingframe.api.extension`, `com.lingframe.api.annotation`)

*   **`ExtensionPoint`**: 标记接口，用于**声明**一个接口是可被扩展的。它告诉框架，“这个接口是设计用来被不同插件提供不同实现的”。
*   **`@Extension`**: 标记注解，用于**声明**一个类是某个 `ExtensionPoint` 的具体实现。框架将自动发现并注册这些扩展实现，并支持 `ordinal` 排序和 `description` 描述。

### 2.4 事件机制 (`com.lingframe.api.event`)

*   **`LingEvent`**: 标记接口，所有跨插件传播的事件都应实现此接口。
*   **`LingEventListener<E extends LingEvent>`**: 这是一个扩展点，插件通过实现此接口并标记 `@Extension` 来监听系统事件，实现插件间的解耦通信。

### 2.5 异常处理 (`com.lingframe.api.exception`)

*   **`LingException`**: 框架的基础运行时异常。
*   **`PermissionDeniedException`**: 当插件尝试执行未经授权的操作时抛出的异常，继承自 `LingException`。

### 2.6 权限安全 (`com.lingframe.api.security`)

*   **`AccessType`**: 枚举类型，定义了权限检查时的操作类型，如 `READ`, `WRITE`, `EXECUTE`。
*   **`PermissionService`**: Core 提供的权限查询服务接口，包含 `isAllowed` (检查权限)、`getPermission` (获取权限配置) 和 `audit` (记录审计日志) 方法。这是实现框架“零信任”和权限仲裁的核心接口。

## 3. 权限管理设计理念 (分层控制)

LingFrame 的权限管理采用**分层控制**的理念，旨在实现强大的安全保障同时保持 Core 的精简和插件的自治：

*   **Core 层 (仲裁者)**：
    *   负责解析和存储插件的权限配置 (`plugin.yml`)。
    *   提供统一的 `PermissionService` 接口供查询。
    *   收集和存储审计日志。
    *   通过 Spring 上下文管理，控制 Bean 的可见性。
    *   提供**粗粒度兜底拦截**，作为最后一道防线。
*   **基础设施插件层 (执行者)**：
    *   最了解自身 API，负责**细粒度拦截**（如 MyBatis 拦截器、Redis AOP）。
    *   调用 Core 的 `PermissionService` 进行权限判断。
    *   将操作和结果上报给 Core 进行审计。
    *   定义读写操作的分类。
*   **业务插件层**：完全受控，只能通过 `PluginContext` 获取授权服务，无法绕过权限检查。

这种设计确保了 Core 保持精简，基础设施插件能够自治并实现细粒度控制，同时通过 Core 的兜底机制提供了强大的安全保障。

## 4. 扩展点设计理念 (最小侵入性与透明性)

LingFrame 追求**透明的插件化**，即在提供强大扩展能力的同时，最大限度地降低对现有代码的侵入性，并减少开发者的学习成本。

*   **`ExtensionPoint` 的新定位**：不再是强制插件实现某个接口才能被框架识别的“适配器”，而更多地成为一种**“声明”**。
    *   **声明一个接口是可被扩展的**：告诉框架该接口可有多种实现。
    *   **声明一个服务是可被替换的**：告诉框架该服务可有多个实现，框架根据策略选择。
*   **实现方式**：
    *   **服务发现与替换**：业务代码通过 `PluginContext.getService()` 获取服务接口，框架在运行时自动发现并注入 `@Extension` 标记的实现。
    *   **事件监听**：业务代码发布 `LingEvent`，框架自动分发给所有 `@Extension` 标记的 `LingEventListener` 实现。
    *   **特定功能钩子**：核心业务流程在关键节点通过框架调用所有 `@Extension` 标记的钩子实现。
*   **非侵入性技术**：结合 AOP、Spring Bean 生命周期管理、字节码增强等技术，在不修改原有代码逻辑的前提下，实现权限仲裁、服务调度等功能，最大限度地减少对开发者原有习惯的改变。

## 5. 解决“依赖地狱”和“版本冲突”

LingFrame 通过以下机制解决模块化系统中的常见挑战：

*   **`lingframe-dependencies` 模块**：统一管理所有核心库的版本，减少版本冲突。
*   **`PluginContext.getService()`**：实现服务提供者和消费者的解耦，由 Core 仲裁服务实例，避免插件间直接依赖具体实现。
*   **Spring 父子上下文与类加载器隔离**：为每个插件提供独立的运行环境，允许不同插件依赖不同版本的库，从而有效解决版本冲突问题。

通过上述设计，LingFrame 旨在构建一个**可控、可扩展、可演进**的 JVM 插件化框架，同时兼顾开发者的使用体验和系统的稳定性。