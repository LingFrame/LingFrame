# 生态系统 SPI 扩展指南 (Ecosystem SPI Extension Guide)

灵珑 (LingFrame) 核心框架遵循微内核理念，专注于高阶类隔离和组件生命周期管理底座。任何与具体业务、环境或是分布式中间件（比如 Nacos 注册中心、Zipkin 分布式追踪或 Apollo 配置中心）绑定的功能，都必须通过**生态扩展机制 (Ecosystem SPI)** 无侵入接入。

本文档将指导开发者如何使用 `lingframe-runtime` 阶段预埋的各种扩展点，搭建适配自己企业级基础设施体系的外骨骼实现。

---

## 1. 拦截服务调用轨迹 (`LingInvocationFilter`)

如果您需要针对被 `@LingService` 注解修饰的服务方法进行统一流量捕获（如 OpenTelemetry 分布式链路追踪增强，或全局监控 Metrics 指数上报），此 SPI 将是最首选入口。

### 工作原理
`FilterableLingServiceInvoker` 是默认注入的 `LingServiceInvoker`，其具备组合责任链的能力，所有发现自 Spring Boot 环境的其他 `LingInvocationFilter` 实现会被自动识别拦截执行流。

### 开发示例

```java
import com.lingframe.starter.spi.LingInvocationFilter;
import com.lingframe.core.ling.LingInstance;
import org.springframework.stereotype.Component;
import java.lang.reflect.Method;

@Component
public class TraceLingInvocationFilter implements LingInvocationFilter {

    @Override
    public int getOrder() {
        return 0; // 支持 Ordered 接口定义拦截顺序
    }

    @Override
    public Object filter(LingInstance instance, Object bean, Method method, Object[] args, FilterChain chain) throws Exception {
        String lingId = instance.getLingId();
        String methodName = method.getName();
        
        // 1. 织入调用前文 Context 等等...
        System.out.println("-> Intercepting request for Ling [" + lingId + "] method " + methodName);
        long start = System.currentTimeMillis();
        
        try {
            // 2. 将控制流传递给原始服务或下一个 Filter
            return chain.proceed(instance, bean, method, args);
        } finally {
            // 3. 结果监控拦截埋点...
            System.out.println("<- Executed Ling [" + lingId + "] method " + methodName + " in " + (System.currentTimeMillis() - start) + "ms");
        }
    }
}
```

---

## 2. 导出灵元服务点 (`ServiceExporter`)

当您的 Spring 宿主应用需要感知内部沙箱运行的各个 Lings 对外暴露出新的扩展能力或服务点时（例如将这些微服务主动上发注册登记至诸如 Consul 或 Nacos 等远端注册中心），使用 `ServiceExporter` 这是针对微内核生命周期广播监听的标准入口。

### 工作原理
`ServiceExporterListener` 自动订阅了底层框架在 Ling 发生启停时透出的内部管理类事件，并抽取提取该模块向外导出的全部定义元数据，下发给 `ServiceExporter` 接口体系。

### 开发示例

```java
import com.lingframe.starter.spi.ServiceExporter;
import com.lingframe.api.config.LingDefinition;
import com.lingframe.core.ling.InvokableService;
import org.springframework.stereotype.Component;

@Component
public class NacosServiceExporter implements ServiceExporter {

    @Override
    public void export(LingDefinition definition, Map<String, InvokableService> services) {
        String serviceVersion = definition.getVersion();
        
        System.out.println("Exporting newly active services for Ling " + definition.getId() + " v" + serviceVersion);
        services.forEach((serviceId, serviceRef) -> {
            // 发起远程 HTTP 请求调用 Nacos Open API 将该局部微服务标识为可用状态
            System.out.println("   -> Register to Nacos: " + serviceId);
        });
    }

    @Override
    public void unexport(LingDefinition definition) {
        // Ling 模块被下线撤出/升级摘除，此处需将 Nacos 中登记的服务标识废弃摘流
        System.out.println("Unexporting services for Ling " + definition.getId());
    }
}
```

---

## 3. 定制化子容器上下文扩展 (`LingContextCustomizer`)

当内部每一个被隔离开运行的沙箱（如 Spring Boot 运行时子级容器 Context）被创建并装配刷新（`refresh()` 前夕）时，您可以通过该扩展为容器注入诸如自定义数据源代理（DataSourceProxy）或者全局的 Environment 属性与配置覆盖逻辑。

### 工作原理
构建一个专属的环境 `SpringApplicationBuilder` 时，`SpringContainerFactory` 统总所有实现 `LingContextCustomizer` 的对象，在启动挂载前后执行切面介入。

### 开发示例

```java
import com.lingframe.starter.spi.LingContextCustomizer;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.stereotype.Component;
import java.util.Properties;

@Component
public class ApolloLingContextCustomizer implements LingContextCustomizer {

    @Override
    public void customize(SpringApplicationBuilder builder, String lingId, String version) {
        // 动态覆盖沙箱环境内部所需的连接字与属性文件
        Properties overrides = new Properties();
        overrides.put("ling.environment.isolated", "true");
        // 假设可以向 builder 注入 Apollo 动态配置客户端...
        
        builder.properties(overrides);
        System.out.println("Customizing Context properties for " + lingId + "-" + version);
    }
}
```

---

## 4. 全局拉取与安装代理 (`LingDeployService`)

如果您使用的包管理或模块下发生态具有自定义制品仓库体系（如 OSS 对象存储、或者企业私有的 Git 内部发布站服务），框架推荐由宿主（而非底层的微内核沙箱管理器）去负责下载拉取压缩包后，再桥接调用核心进行热插拔安装。

### 工作原理
`LingDeployService` 是 `LingManager` 与开发者业务服务之间的一座桥上建筑级代理。你可以不直接操纵本地 `File` 对象，而是对包标识版本参数直接发号施令。`defaultLingDeployService` 为该功能提供基本的存根样板。

### 开发示例

您可以重写并定义自己的 Bean 来覆盖原实现：

```java
import com.lingframe.starter.deploy.LingDeployService;
import com.lingframe.api.config.LingDefinition;
import org.springframework.stereotype.Service;
import java.io.File;

@Service
public class OssLingDeployService implements LingDeployService {

    @Override
    public void deploy(LingDefinition definition, String packageUri) throws Exception {
        System.out.println("Fetching package [" + definition.getId() + "] from OSS URL: " + packageUri);
        
        // 【关键实现】发起 AWS/Aliyun OSS SDK 传输拉取，下载为一个本地临时 file 对象
        File downloadedJar = fetchFromOss(packageUri); 
        
        // 交棒回原生微内核：进行安全的沙箱挂载及 Classloader 解析
        lingManager.install(definition, downloadedJar);
    }
    
    private File fetchFromOss(String uri) {
       // ... OSS downloading logic
       return new File("/tmp/downloaded-plugin.jar");
    }
}
```

---

结语：生态扩展接口 (Ecosystem SPI) 为业务提供最非侵入式的手段以掌控各个 Ling 容器实例的流量、生命周期、加载属性与外部寻址导出体系。只要实现对应的接口并在宿主的 Spring 容器中受管，LingFrame 的基础设施即可在感知时主动完成整合装配。
