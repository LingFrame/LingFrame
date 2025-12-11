# 贡献指南

感谢你对 LingFrame 的关注！我们欢迎任何形式的贡献。

## 开发环境

### 环境要求

- JDK 21+
- Maven 3.8+
- IDE 推荐：IntelliJ IDEA

### 本地构建

```bash
# 克隆仓库
git clone https://github.com/lingframe/lingframe.git
cd lingframe

# 编译安装
mvn clean install

# 跳过测试
mvn clean install -DskipTests
```

## 贡献流程

### 1. 认领任务

- 查看 [Issues](../../issues) 中的待办任务
- 在 Issue 下留言表示你想认领
- 等待维护者分配

### 2. 开发

```bash
# Fork 仓库后克隆
git clone https://github.com/YOUR_USERNAME/lingframe.git

# 创建特性分支
git checkout -b feature/your-feature

# 开发并提交
git add .
git commit -m "feat: add your feature"

# 推送
git push origin feature/your-feature
```

### 3. 提交 PR

- 确保代码通过编译：`mvn clean compile`
- 确保测试通过：`mvn test`
- 提交 Pull Request，描述清楚改动内容

## 代码规范

### 命名约定

| 类型   | 约定                     | 示例                          |
| ------ | ------------------------ | ----------------------------- |
| 接口   | 描述性名称               | `PluginContext`, `LingPlugin` |
| 实现类 | `Default` 或 `Core` 前缀 | `DefaultPermissionService`    |
| 异常   | `Exception` 后缀         | `LingException`               |
| 注解   | 描述性名称               | `@LingService`                |
| 代理类 | `Proxy` 后缀             | `SmartServiceProxy`           |

### 模块依赖

- 新增依赖版本在 `lingframe-dependencies/pom.xml` 中管理
- 各模块通过 BOM 引用版本，不要硬编码版本号

### 代码风格

- 使用 4 空格缩进
- 类和方法添加 Javadoc 注释
- 使用 Lombok 减少样板代码

## 目录结构

```
lingframe/
├── lingframe-api/          # 契约层（只放接口和注解）
├── lingframe-core/         # 核心实现
├── lingframe-runtime/      # 运行时集成
├── lingframe-plugins-infra/# 基础设施插件
└── lingframe-samples/      # 示例代码
```

## 提交信息规范

使用 [Conventional Commits](https://www.conventionalcommits.org/) 格式：

```
<type>: <description>

[optional body]
```

类型：

- `feat`: 新功能
- `fix`: 修复 Bug
- `docs`: 文档更新
- `refactor`: 重构
- `test`: 测试相关
- `chore`: 构建/工具相关

示例：

```
feat: add permission check for SQL execution
fix: fix classloader memory leak on plugin unload
docs: update quick start guide
```

## 问题反馈

- **Bug 报告**：请在 Issues 中使用 Bug 模板
- **功能建议**：请在 Discussions 中讨论
- **安全问题**：请私信维护者，不要公开

## 行为准则

- 尊重每一位贡献者
- 保持友善和专业的交流
- 接受建设性的批评

感谢你的贡献！🎉
