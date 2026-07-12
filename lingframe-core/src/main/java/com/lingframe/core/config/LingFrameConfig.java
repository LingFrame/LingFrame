package com.lingframe.core.config;

import com.lingframe.core.ling.LingRuntimeConfig;
import lombok.Builder;
import lombok.Data;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Field;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 灵珑灵核全局配置对象（Immutable）
 * <p>
 * 职责：作为 Core 层的唯一配置入口，屏蔽 Spring Boot 或其他外部环境的差异。
 * 包含：
 * 1. 全局环境设置 (Environment)
 * 2. 运行时模板 (Runtime Template)
 * 3. 跨 ClassLoader 配置
 */
@Slf4j
@Data
@Builder
@ToString
public class LingFrameConfig implements LingFrameInfo {

    // ================= 全局环境 (Environment) =================

    private static volatile LingFrameConfig INSTANCE;

    // 默认配置（懒加载单例，线程安全 - Lazy Holder 模式）
    private static final class DefaultConfigHolder {
        static final LingFrameConfig DEFAULT = LingFrameConfig.builder().build();
    }

    /**
     * 获取全局配置实例 (静态方法，随处可调)
     * <p>
     * 线程安全说明：如果未初始化，返回线程安全的默认配置单例
     * <p>
     * ⚠️ 兜底后门语义：主路径应通过构造器依赖注入获取 {@link LingFrameConfig}。
     * 此方法仅保留给无法依赖注入的场景（如 SPI 扩展点、dashboard 控制面对运行时单例的读写）。
     * 新增生产代码不应调用此方法，应改用构造器注入。
     */
    public static LingFrameConfig current() {
        LingFrameConfig config = INSTANCE;
        if (config == null) {
            // 未初始化时返回固定的默认配置，保证行为一致性
            return DefaultConfigHolder.DEFAULT;
        }
        return config;
    }

    /**
     * 初始化全局实例 (由 Starter 启动时调用一次)
     * <p>
     * 线程安全：使用 synchronized 防止并发初始化竞态
     * <p>
     * ⚠️ 生产就绪约束：二次调用抛 {@link IllegalStateException}，不再静默拒绝。
     * 历史上这里 return 静默吞掉灵元 Spring 上下文误触发的 init()，但"静默失败"是生产事故温床。
     * 灵元误触发应通过类加载隔离在源头阻止，而非在配置层静默兜底。
     *
     * @throws IllegalStateException 如果已初始化（说明装配链路有 bug，需立即暴露）
     */
    public static synchronized void init(LingFrameConfig config) {
        if (INSTANCE != null) {
            throw new IllegalStateException(
                    "LingFrameConfig already initialized; static init() must be called exactly once by the Starter. "
                            + "If this is thrown during ling loading, the ling's Spring context is incorrectly "
                            + "triggering core config init — fix the classloading isolation instead of catching this.");
        }
        INSTANCE = config;
        checkJdkCompatibility();
    }

    /**
     * 检测 JDK 版本兼容性，JDK 16+ 缺少 --add-opens 时发出警告。
     */
    private static void checkJdkCompatibility() {
        int jdkVersion = jdkMajorVersion();
        if (jdkVersion >= 16) {
            List<String> missing = new ArrayList<>();
            // getDeclaredField 不会因强封装失败，必须尝试 setAccessible 才能检测到访问限制
            try {
                Field targetField = Thread.class.getDeclaredField("target");
                targetField.setAccessible(true);
            } catch (NoSuchFieldException e) {
                // 字段不存在（极端情况），不视为访问限制
            } catch (RuntimeException e) {
                // InaccessibleObjectException (JDK 16+) 是 RuntimeException 子类
                missing.add("--add-opens java.base/java.lang=ALL-UNNAMED");
            }
            try {
                Field driversField = DriverManager.class.getDeclaredField("drivers");
                driversField.setAccessible(true);
            } catch (NoSuchFieldException e) {
                // 字段不存在
            } catch (RuntimeException e) {
                missing.add("--add-opens java.sql/java.sql=ALL-UNNAMED");
            }
            if (!missing.isEmpty()) {
                log.warn("[LingFrame] JDK {} detected strong encapsulation limits, reflection cleanup during ling unloading may fail silently (Metaspace leak risk).\n" +
                        "  Recommended to add the following JVM arguments:\n  {}", jdkVersion, String.join("\n  ", missing));
            } else {
                log.info("[LingFrame] JDK {} --add-opens detection passed, reflection cleanup is available for ling unloading", jdkVersion);
            }
        }
    }

    private static int jdkMajorVersion() {
        String version = System.getProperty("java.specification.version", "1.8");
        if (version.startsWith("1.")) {
            return Integer.parseInt(version.substring(2));
        }
        int dot = version.indexOf('.');
        if (dot > 0) {
            return Integer.parseInt(version.substring(0, dot));
        }
        return Integer.parseInt(version);
    }

    /**
     * 清理全局配置实例。
     * <p>
     * ⚠️ 仅用于测试 teardown，生产环境禁止调用。
     * 测试应优先使用依赖注入的局部 {@link LingFrameConfig} 实例，避免依赖全局静态状态。
     */
    public static synchronized void clear() {
        INSTANCE = null;
    }

    /**
     * 判断全局配置是否已通过 {@link #init} 初始化。
     * <p>
     * 供 Starter 启动时断言、测试前置条件检查使用。
     */
    public static boolean isInitialized() {
        return INSTANCE != null;
    }

    /**
     * 是否开启开发模式 (影响热重载、日志等级、各类检查的宽松度)
     */
    @Builder.Default
    private boolean devMode = false;

    /**
     * 启动时是否自动扫描并加载 home 目录下的灵元。
     */
    @Builder.Default
    private boolean autoScan = true;

    /**
     * 灵元存放根目录
     */
    @Builder.Default
    private String lingHome = "Lings";

    /**
     * 灵元额外目录
     */
    @Builder.Default
    private List<String> lingRoots = Collections.emptyList();

    /**
     * 核心线程数 (用于后台调度器)
     */
    @Builder.Default
    private int corePoolSize = Math.max(2, Runtime.getRuntime().availableProcessors());

    // ================= 灵元线程池预算 =================

    /**
     * 全局灵元线程总预算（所有灵元共享此配额）
     * <p>
     * 每个灵元创建独立线程池时，从此预算中扣减。
     * 卸载时归还。防止灵元线程数不可控膨胀。
     */
    @Builder.Default
    private int globalMaxLingThreads = Runtime.getRuntime().availableProcessors() * 4;

    /**
     * 单个灵元线程池硬上限
     * <p>
     * 即使灵元 ling.yml 中配置了更高的值，也不会超过此上限。
     */
    @Builder.Default
    private int maxThreadsPerLing = 8;

    /**
     * 单个灵元默认线程数
     * <p>
     * 当灵元未在 ling.yml 中指定线程数时，使用此默认值。
     */
    @Builder.Default
    private int defaultThreadsPerLing = 2;

    // ================= 灵核治理配置 =================

    /**
     * 是否启用灵核 Bean 治理，默认值为 false
     * <p>
     * 当为 true 时，启用治理，对灵核 Bean 进行权限检查和审计
     * <p>
     * 当为 false 时，禁用治理，灵核 Bean 不受限制
     */
    @Builder.Default
    private boolean lingCoreGovernanceEnabled = false;

    /**
     * 是否对灵核内部调用进行治理，默认值为 false
     * <p>
     * 当为 true 时，灵核自己调用自己的 Bean 也会被治理
     * <p>
     * 当为 false 时，只有灵元调用灵核 Bean 时才会被治理
     */
    @Builder.Default
    private boolean lingCoreGovernanceInternalCalls = false;

    /**
     * 是否对灵核应用进行权限检查，默认值为 false
     * <p>
     * 当为 true 时，灵核应用也需要通过权限检查
     * <p>
     * 当为 false 时，灵核应用自动拥有所有权限
     */
    @Builder.Default
    private boolean lingCoreCheckPermissions = false;

    @Builder.Default
    private int leakDetectionMaxConcurrentAggressiveChecks = 2;

    @Builder.Default
    private int leakDetectionDevStartDelayMillis = 2000;

    @Builder.Default
    private int leakDetectionAggressiveGcRounds = 5;

    @Builder.Default
    private int leakDetectionAggressiveGcIntervalMillis = 500;

    @Builder.Default
    private int leakDetectionPassiveWindowMillis = 60000;

    @Builder.Default
    private int leakDetectionFinalConfirmationDelayMillis = 1000;

    @Builder.Default
    private int leakDetectionQueuePollMillis = 5000;

    // ================= 共享 API 配置 =================

    /**
     * 预加载的 API JAR 文件路径列表
     * <p>
     * 这些 JAR 会在启动时加载到 SharedApiClassLoader 中，
     * 实现跨灵元的 API 类共享
     * <p>
     * 路径支持：
     * - 绝对路径: /path/to/api.jar
     * - 相对路径: libs/order-api.jar (相对于 lingHome)
     * - Maven 灵元: lingframe-examples/lingframe-example-order-api (开发模式)
     */
    @Builder.Default
    private List<String> preloadApiJars = new ArrayList<>();

    /**
     * 是否启用 API 包覆盖检测。
     * <p>
     * 当为 true 时，如果灵元包内包含 `com.lingframe.api.*` 类则拒绝安装
     * 当为 false 时，允许灵元包内包含同名 API（不建议）
     */
    @Builder.Default
    private boolean apiOverrideCheckEnabled = true;

    /**
     * 灵元服务隐式接口注册开关（默认 true）。
     * <p>
     * 为 true 时：灵元 Bean 实现的业务接口会自动注册为 FQSID=[lingId]:[interfaceName]，
     * 实现「implements UserService 即暴露」的零侵入契约。
     * 为 false 时：仅显式 @LingService 标注的方法/类型会注册，
     * 适用于想强制显式声明、或避免误扫到框架接口的团队。
     */
    @Builder.Default
    private boolean implicitRegistration = true;

    // ================= 运行时模板 (Runtime Template) =================

    /**
     * 灵元运行时的默认配置模板
     * (当创建新灵元实例时，会应用此配置)
     */
    @Builder.Default
    private LingRuntimeConfig runtimeConfig = LingRuntimeConfig.defaults();

    /**
     * 灵元运行时默认超时（毫秒），实现 {@link LingFrameInfo}。
     */
    @Override
    public int getDefaultTimeout() {
        LingRuntimeConfig rc = runtimeConfig;
        return rc != null ? rc.getDefaultTimeoutMs() : 3000;
    }

}
