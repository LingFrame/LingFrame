package com.lingframe.starter.adapter;

import com.lingframe.api.annotation.Auditable;
import com.lingframe.api.annotation.LingService;
import com.lingframe.api.annotation.RequiresPermission;
import com.lingframe.api.context.PluginContext;
import com.lingframe.api.plugin.LingPlugin;
import com.lingframe.core.context.CorePluginContext;
import com.lingframe.core.plugin.PluginManager;
import com.lingframe.core.spi.PluginContainer;
import com.lingframe.core.strategy.GovernanceStrategy;
import com.lingframe.starter.web.WebInterfaceManager;
import com.lingframe.starter.web.WebInterfaceMetadata;
import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.util.ReflectionUtils;
import org.springframework.web.bind.annotation.*;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
public class SpringPluginContainer implements PluginContainer {

    private final SpringApplicationBuilder builder;
    private ConfigurableApplicationContext context;
    private final ClassLoader classLoader;
    // 保存 Context 以便 stop 时使用
    private PluginContext pluginContext;

    // 🔥【新增】实例化一个发现器
    private final ParameterNameDiscoverer nameDiscoverer = new DefaultParameterNameDiscoverer();

    public SpringPluginContainer(SpringApplicationBuilder builder, ClassLoader classLoader) {
        this.builder = builder;
        this.classLoader = classLoader;
    }

    @Override
    public void start(PluginContext pluginContext) {
        this.pluginContext = pluginContext;

        // 1. TCCL 劫持
        Thread t = Thread.currentThread();
        ClassLoader old = t.getContextClassLoader();
        t.setContextClassLoader(classLoader);
        try {
            // 2. 启动 Spring
            this.context = builder.run();

            // 3. 【关键】寻找并触发 LingPlugin 生命周期
            // 尝试从 Spring 容器中获取实现了 LingPlugin 接口的 Bean
            try {
                LingPlugin plugin = this.context.getBean(LingPlugin.class);
                log.info("Triggering onStart for plugin: {}", pluginContext.getPluginId());
                plugin.onStart(pluginContext);

                // 3. 扫描 @LingService 并注册到 Core
                // 等待所有Bean初始化完成后再注册服务
                scheduleServiceRegistration();
            } catch (Exception e) {
                log.warn("No LingPlugin entry point found in plugin: {}", pluginContext.getPluginId());
            }

        } finally {
            t.setContextClassLoader(old);
        }
    }

    /**
     * 延迟服务注册，确保所有Bean都已初始化完成
     */
    private void scheduleServiceRegistration() {
        // 使用Spring的事件机制，在所有Bean初始化完成后注册服务
        if (context instanceof ConfigurableApplicationContext cxt) {
            cxt.addApplicationListener(event -> {
                if (event instanceof org.springframework.context.event.ContextRefreshedEvent) {
                    log.info("All beans initialized, registering LingServices for plugin: {}", pluginContext.getPluginId());
                    // 注册 RPC 服务
                    scanAndRegisterLingServices();
                    // 注册 Web Controller
                    scanAndRegisterControllers();
                }
            });
        } else {
            // 兜底方案：延迟注册
            Thread delayRegistrationThread = new Thread(() -> {
                try {
                    Thread.sleep(1000); // 等待1秒确保初始化完成
                    scanAndRegisterLingServices();
                    scanAndRegisterControllers();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            delayRegistrationThread.setDaemon(true);
            delayRegistrationThread.start();
        }
        scanAndRegisterLingServices();
        scanAndRegisterControllers();
    }

    /**
     * 利用 Spring 工具类扫描所有 Bean 中的协议服务
     */
    private void scanAndRegisterLingServices() {
        if (!(pluginContext instanceof CorePluginContext)) {
            log.warn("PluginContext is not instance of CorePluginContext, cannot register services.");
            return;
        }
        PluginManager pluginManager = ((CorePluginContext) pluginContext).getPluginManager();
        String pluginId = pluginContext.getPluginId();

        // 获取容器中所有 Bean 的名称
        String[] beanNames = context.getBeanDefinitionNames();

        for (String beanName : beanNames) {
            try {
                Object bean = context.getBean(beanName);
                // 处理 AOP 代理，获取目标类
                Class<?> targetClass = AopUtils.getTargetClass(bean);

                // 遍历所有方法，查找 @LingService
                ReflectionUtils.doWithMethods(targetClass, method -> {
                    // 使用 AnnotatedElementUtils 支持元注解和代理覆盖
                    LingService lingService = AnnotatedElementUtils.findMergedAnnotation(method, LingService.class);
                    if (lingService != null) {
                        // 组装 FQSID: [PluginID]:[ShortID]
                        String shortId = lingService.id();
                        String fqsid = pluginId + ":" + shortId;

                        // 上报给 Core
                        pluginManager.registerProtocolService(pluginId, fqsid, bean, method);
                    }
                });
            } catch (Exception e) {
                log.warn("Error scanning bean {} for LingServices", beanName, e);
            }
        }
    }

    /**
     * 扫描并解析 @RestController
     */
    private void scanAndRegisterControllers() {
        if (!(pluginContext instanceof CorePluginContext)) return;
        String pluginId = pluginContext.getPluginId();

        // 1. 获取所有 @RestController
        Map<String, Object> controllers = context.getBeansWithAnnotation(RestController.class);

        for (Object bean : controllers.values()) {
            try {
                Class<?> targetClass = AopUtils.getTargetClass(bean);

                // 2. 解析类级 @RequestMapping
                String baseUrl = "";
                RequestMapping classMapping = AnnotatedElementUtils.findMergedAnnotation(targetClass, RequestMapping.class);
                if (classMapping != null && classMapping.path().length > 0) {
                    baseUrl = classMapping.path()[0];
                }

                // 3. 遍历方法
                String finalBaseUrl = baseUrl;
                ReflectionUtils.doWithMethods(targetClass, method -> {
                    // 查找 RequestMapping (包含 GetMapping, PostMapping 等)
                    RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
                    if (mapping != null) {
                        registerControllerMethod(pluginId, bean, method, finalBaseUrl, mapping);
                    }
                });
            } catch (Exception e) {
                log.error("Failed to parse controller bean in plugin: {}", pluginId, e);
            }
        }
    }

    /**
     * 解析单个方法并生成元数据
     */
    private void registerControllerMethod(String pluginId, Object bean, Method method, String baseUrl, RequestMapping mapping) {
        // 1. URL 拼接: /pluginId/classUrl/methodUrl
        String methodUrl = mapping.path().length > 0 ? mapping.path()[0] : "";
        String fullPath = ("/" + pluginId + "/" + baseUrl + "/" + methodUrl).replaceAll("/+", "/");

        // 2. HTTP Method
        String httpMethod = mapping.method().length > 0 ? mapping.method()[0].name() : "GET"; // 默认 GET

        // 3. 解析参数 (为三段式绑定做准备)
        // 🔥【修改】获取真实的参数名列表 (开启 -parameters 后这里就能拿到了)
        String[] paramNames = nameDiscoverer.getParameterNames(method);
        Parameter[] parameters = method.getParameters();

        List<WebInterfaceMetadata.ParamDef> params = new ArrayList<>();

        for (int i = 0; i < parameters.length; i++) {
            Parameter p = parameters[i];
            WebInterfaceMetadata.ParamType type = WebInterfaceMetadata.ParamType.UNKNOWN;

            // 【核心逻辑】名字获取优先级：
            // 1. 注解显式指定 @PathVariable("uid")
            // 2. 编译器保留的参数名 (开启 -parameters 后)
            // 3. 字节码解析 (ASM)
            // 4. 原生反射 (arg0)
            String name = p.getName(); // 默认 arg0
            if (paramNames != null && paramNames.length > i && paramNames[i] != null) {
                name = paramNames[i];  // 拿到真实名字 id
            }

            if (p.isAnnotationPresent(PathVariable.class)) {
                type = WebInterfaceMetadata.ParamType.PATH_VARIABLE;
                String val = p.getAnnotation(PathVariable.class).value();
                if (!val.isEmpty()) name = val; // 如果注解指定了名字，优先级最高
            } else if (p.isAnnotationPresent(RequestBody.class)) {
                type = WebInterfaceMetadata.ParamType.REQUEST_BODY;
            } else if (p.isAnnotationPresent(RequestParam.class)) {
                type = WebInterfaceMetadata.ParamType.REQUEST_PARAM;
                String val = p.getAnnotation(RequestParam.class).value();
                if (!val.isEmpty()) name = val;
            }

            params.add(WebInterfaceMetadata.ParamDef.builder()
                    .name(name)
                    .type(p.getType())
                    .sourceType(type)
                    .build());
        }

        // 🔥 1. 智能权限推导
        String permission;
        RequiresPermission permAnn = AnnotatedElementUtils.findMergedAnnotation(method, RequiresPermission.class);
        if (permAnn != null) {
            permission = permAnn.value();
        } else {
            // 如果没有注解，根据 URL 或方法名推导
            // 例如：POST /user/create -> user:create
            // 这里简单复用 Strategy，或者你可以写针对 Web 的推导逻辑
            permission = GovernanceStrategy.inferPermission(method);
        }

        // 🔥 2. 智能审计推导
        boolean shouldAudit = false;
        String auditAction = method.getName();
        Auditable auditAnn = AnnotatedElementUtils.findMergedAnnotation(method, Auditable.class);

        if (auditAnn != null) {
            shouldAudit = true;
            auditAction = auditAnn.action();
        } else {
            // Web请求通常如果是 POST/PUT/DELETE 默认审计
//            String httpMethod = mapping.method().length > 0 ? mapping.method()[0].name() : "GET";
            if (!"GET".equals(httpMethod)) {
                shouldAudit = true;
                auditAction = httpMethod + " " + fullPath;
            }
        }

        // 4. 构建元数据
        WebInterfaceMetadata metadata = WebInterfaceMetadata.builder()
                .pluginId(pluginId)
                .targetBean(bean)
                .targetMethod(method)
                .classLoader(this.classLoader)
                .urlPattern(fullPath)
                .httpMethod(httpMethod)
                .parameters(params)
                .requiredPermission(permission) // 保存推导结果
                .shouldAudit(shouldAudit)
                .auditAction(auditAction)
                .build();

        // 5. 打印验证
        log.info("🌍 [LingFrame Web] Found Controller: {} [{}] -> Params: {}",
                httpMethod, fullPath, params.size());

        // 注册时调用 Starter 包里的 Manager
        if (WebInterfaceManager.getInstance() != null) {
            WebInterfaceManager.getInstance().register(metadata);
        }
    }

    @Override
    public void stop() {
        if (context != null && context.isActive()) {
            // 1. 【关键】触发 onStop
            try {
                LingPlugin plugin = this.context.getBean(LingPlugin.class);
                log.info("Triggering onStop for plugin: {}", pluginContext.getPluginId());
                plugin.onStop(pluginContext);
            } catch (Exception e) {
                // 忽略，可能没有入口类
            }

            context.close();
        }
        this.context = null;
    }

    @Override
    public boolean isActive() {
        return context != null && context.isActive();
    }

    @Override
    public <T> T getBean(Class<T> type) {
        if (!isActive()) return null;
        try {
            return context.getBean(type);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public Object getBean(String beanName) { // <--- 新增此方法实现
        if (!isActive()) return null;
        try {
            return context.getBean(beanName); // 调用 Spring 自身的 getBean(String)
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public ClassLoader getClassLoader() {
        return this.classLoader;
    }
}