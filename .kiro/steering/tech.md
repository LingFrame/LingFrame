# 技术栈

## 构建系统

- **Maven**：多模块项目，采用父 POM 结构
- **Java 21**：目标 JDK 版本
- **Maven CI Friendly**：使用 `${revision}` 变量进行版本管理

## 核心技术

- **Spring Boot 3.5.6**：主要运行时框架
- **Spring Context**：父子上下文隔离实现插件管理
- **MyBatis Flex 1.11.4**：数据库 ORM 层
- **Guava 33.4.8**：工具库
- **Caffeine 3.2.1**：高性能缓存
- **SnakeYAML 2.2**：配置解析
- **SLF4J 2.0.17**：日志抽象

## 开发工具

- **Lombok 1.18.30**：代码生成（provided 作用域）
- **JUnit Jupiter 5.10.1**：测试框架
- **Mockito 5.8.0**：模拟框架

## 常用命令

### 构建

```bash
# 清理并编译所有模块
mvn clean compile

# 打包所有模块
mvn clean package

# 安装到本地仓库
mvn clean install

# 跳过测试构建
mvn clean install -DskipTests
```

### 测试

```bash
# 运行所有测试
mvn test

# 运行特定模块测试
mvn test -pl lingframe-core

# 运行特定测试类
mvn test -Dtest=PluginManagerTest
```

### 开发

```bash
# 生成 IDE 项目文件
mvn idea:idea

# 检查依赖更新
mvn versions:display-dependency-updates

# 扁平化 POM 以支持 CI 友好版本
mvn flatten:flatten
```

## 模块依赖

- 所有模块继承自 `lingframe-dependencies` 进行版本管理
- 使用 `lingframe-bom` 进行外部依赖管理
- 核心模块：`lingframe-api` → `lingframe-core` → 运行时集成

## 仓库配置

- 使用阿里云 Maven 仓库加速中国地区下载
- 配置 Lombok 注解处理器支持
- 强制使用 UTF-8 编码
