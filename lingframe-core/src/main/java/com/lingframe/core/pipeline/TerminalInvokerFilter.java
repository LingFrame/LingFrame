package com.lingframe.core.pipeline;

import com.lingframe.api.exception.LingInvocationException;
import com.lingframe.core.invoker.FastLingServiceInvoker;
import com.lingframe.core.ling.InvokableMethodCache;
import com.lingframe.core.ling.LingInstance;
import com.lingframe.core.model.EngineTrace;
import com.lingframe.core.spi.LingFilterChain;
import com.lingframe.core.spi.LingInvocationFilter;
import com.lingframe.core.spi.LingServiceInvoker;
import lombok.extern.slf4j.Slf4j;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;

/**
 * 终端调用过滤器。
 * 负责在 Pipeline 末端拿到真实 Bean、MethodHandle 并执行最终调用。
 * <p>
 * ⚠️ 这是“真正落地副作用”的最后一环，所以它必须严格消费前面阶段的显式分区结果，
 * 而不能再回退到 attachment key 或额外的隐式推导。
 */
@Slf4j
public class TerminalInvokerFilter implements LingInvocationFilter {

    /**
     * 方法句柄缓存由引擎统一持有，避免终端过滤器自己持久化目标实例。
     */
    private final InvokableMethodCache methodCache;
    private final LingServiceInvoker invoker;

    public TerminalInvokerFilter(InvokableMethodCache methodCache, LingServiceInvoker invoker) {
        this.methodCache = methodCache;
        this.invoker = invoker != null ? invoker : new FastLingServiceInvoker();
    }

    @Override
    public int getOrder() {
        return FilterPhase.TERMINAL;
    }

    @Override
    public Object doFilter(InvocationContext ctx, LingFilterChain chain) throws Throwable {
        // 【关键】GOVERN_ONLY 代表“借道治理，不借道执行”。
        // 灵核 Web / AOP 入口只需要让 Pipeline 完成治理校验，真实业务执行仍由 Spring / Servlet 原链路继续完成。
        if (ctx.execution().getMode().isGovernOnly()) {
            log.debug("[TerminalInvoker] Governance-only mode, skipping terminal invocation for {}", ctx.getServiceFQSID());
            return null;
        }

        // ⚠️ 终端执行只从显式协议分区读取目标实例和解析结果，不再依赖 attachments + magic key
        LingInstance target = ctx.routing().getTargetInstance();
        Class<?>[] resolvedTypes = ctx.resolution().getResolvedParameterTypes();

        // SIMULATION 契约级干跑（无具体方法名：压测 / 资源模拟）：路由目标存在即视为模拟成功，
        // 不解析真实 Bean 与 MethodHandle——契约级入口没有方法名，getMethod(null, ...) 必然失败；
        // 资源模拟没有 FQSID，getServiceBean 也必然取不到 Bean。终端不真实执行，验证到路由层即可。
        if (ctx.execution().getMode().isSimulation()
                && (ctx.getMethodName() == null || ctx.getMethodName().isEmpty())) {
            if (target == null) {
                String action = ctx.governance().getAuditAction() != null ? ctx.governance().getAuditAction() : "UNKNOWN";
                ctx.execution().addTrace(EngineTrace.builder()
                        .source("TerminalInvokerFilter")
                        .action("🛡️ Simulation completed without concrete route target, action=" + action)
                        .type("OK")
                        .depth(10)
                        .build());
                return "Simulation Success: " + action;
            }
            ctx.execution().addTrace(EngineTrace.builder()
                    .source("TerminalInvokerFilter")
                    .action("🛡️ Simulation reached terminal target " + target.getLingId())
                    .type("OK")
                    .depth(10)
                    .build());
            String simulatedTarget = ctx.getServiceFQSID() != null ? ctx.getServiceFQSID() : target.getLingId();
            return "Simulation Success for: " + simulatedTarget;
        }

        if (target == null || resolvedTypes == null) {
            if (ctx.execution().getMode().isSimulation()) {
                String action = ctx.governance().getAuditAction() != null ? ctx.governance().getAuditAction() : "UNKNOWN";
                ctx.execution().addTrace(EngineTrace.builder()
                        .source("TerminalInvokerFilter")
                        .action("🛡️ Simulation completed without concrete route target, action=" + action)
                        .type("OK")
                        .depth(10)
                        .build());
                return "Simulation Success: " + action;
            }
            throw new LingInvocationException(ctx.getServiceFQSID(), LingInvocationException.ErrorKind.INTERNAL_ERROR);
        }

        Object serviceBean = getServiceBean(target, ctx);
        if (serviceBean == null) {
            throw new LingInvocationException(ctx.getServiceFQSID(), LingInvocationException.ErrorKind.ROUTE_FAILURE);
        }

        // MethodHandle 可以缓存，但不能把目标 Bean 本身长期缓存在核心层，否则卸载时最容易形成强引用链
        // cacheKey 必须稳定标识「目标实例 + 契约 + 方法」：
        // L0 路由不改写 FQSID，裸 contractId 场景下 ctx.getServiceFQSID() 可能不含 lingId 前缀，
        // 故改用 ctx.getEffectiveLingId() + ":" + 裸契约名拼装，避免 cacheKey 漂移。
        // 裸契约名取 FQSID 冒号后部分；无冒号时取 FQSID 本身（即裸 contractId）。
        String fqsid = ctx.getServiceFQSID();
        int colonIdx = fqsid != null ? fqsid.indexOf(':') : -1;
        String contractPart = colonIdx > 0 && colonIdx < fqsid.length() - 1 ? fqsid.substring(colonIdx + 1) : fqsid;
        String cacheKey = target.getLingId() + ":" + target.getVersion() + "@"
                + ctx.getEffectiveLingId() + ":" + contractPart + "#"
                + ctx.getMethodName();
        MethodHandle handle = methodCache.computeIfAbsent(cacheKey, key -> resolveMethodHandle(ctx, serviceBean, resolvedTypes, key));

        if (ctx.execution().getMode().isSimulation()) {
            ctx.execution().addTrace(EngineTrace.builder()
                    .source("TerminalInvokerFilter")
                    .action("🛡️ Simulation reached terminal target " + cacheKey)
                    .type("OK")
                    .depth(10)
                    .build());
            return "Simulation Success for: " + ctx.getServiceFQSID() + "#" + ctx.getMethodName();
        }

        int configuredRetryCount = Math.max(0, ctx.governance().getRetryCount() == null ? 0 : ctx.governance().getRetryCount());
        int maxAttempts = configuredRetryCount + 1;
        Throwable lastFailure = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return invokeTerminalTarget(ctx, target, serviceBean, handle, resolvedTypes);
            } catch (Throwable throwable) {
                lastFailure = unwrapInvocationFailure(ctx, throwable);
                if (!shouldRetry(lastFailure) || attempt >= maxAttempts) {
                    String fallbackValue = ctx.governance().getFallbackValue();
                    if (fallbackValue != null) {
                        log.warn("[TerminalInvoker] Invocation failed after {} attempt(s), returning fallback for {}",
                                attempt, ctx.getServiceFQSID());
                        return convertFallbackValue(fallbackValue, handle.type().returnType(), ctx);
                    }
                    throw lastFailure;
                }
                log.warn("[TerminalInvoker] Invocation failed on attempt {}/{}, retrying {}",
                        attempt, maxAttempts, ctx.getServiceFQSID(), lastFailure);
            }
        }
        throw lastFailure;
    }

    private Object invokeTerminalTarget(InvocationContext ctx, LingInstance target, Object serviceBean,
                                        MethodHandle handle, Class<?>[] resolvedTypes) throws Throwable {
        if (invoker instanceof FastLingServiceInvoker) {
            Object[] args = concatArgs(serviceBean, ctx.getArgs());
            return ((FastLingServiceInvoker) invoker).invokeFast(target, handle, args);
        }

        Method method = serviceBean.getClass().getMethod(ctx.getMethodName(), resolvedTypes);
        return invoker.invoke(target, serviceBean, method, ctx.getArgs());
    }

    private Throwable unwrapInvocationFailure(InvocationContext ctx, Throwable throwable) {
        if (throwable instanceof LingInvocationException) {
            return throwable;
        }
        return new LingInvocationException(ctx.getServiceFQSID(),
                LingInvocationException.ErrorKind.INVOKE_ERROR, throwable);
    }

    /**
     * 将 fallback 配置字符串转换为目标方法返回类型。
     * <p>
     * fallbackValue 在配置层是 {@code String}，而真实方法可能返回 {@code int}/{@code boolean} 等
     * 非 String 类型。若不转换直接返回，调用方在类型转换处会抛 {@code ClassCastException}，
     * 掩盖「回退值类型不匹配」这一配置错误。这里按句柄返回类型做显式转换：
     * <ul>
     *   <li>返回类型为 String / Object / 无法识别时——原样返回（String 语义兜底）</li>
     *   <li>返回类型为基本类型或包装类型——解析对应字符串</li>
     *   <li>其余复杂类型不支持——拒绝并抛 {@link LingInvocationException}，避免静默 ClassCastException</li>
     * </ul>
     */
    private Object convertFallbackValue(String fallbackValue, Class<?> returnType, InvocationContext ctx) {
        if (returnType == null || returnType == String.class || returnType == Object.class) {
            return fallbackValue;
        }
        try {
            if (returnType == int.class || returnType == Integer.class) {
                return Integer.parseInt(fallbackValue.trim());
            }
            if (returnType == long.class || returnType == Long.class) {
                return Long.parseLong(fallbackValue.trim());
            }
            if (returnType == double.class || returnType == Double.class) {
                return Double.parseDouble(fallbackValue.trim());
            }
            if (returnType == float.class || returnType == Float.class) {
                return Float.parseFloat(fallbackValue.trim());
            }
            if (returnType == boolean.class || returnType == Boolean.class) {
                // parseBoolean 对任意非 "true" 值静默返回 false（"ture"/"1"/"yes" 均不误报），
                // 与数值分支的「解析失败显式拒绝」语义不一致；这里显式校验，配置拼错时拒绝而非静默降级
                String trimmed = fallbackValue.trim();
                if (!"true".equalsIgnoreCase(trimmed) && !"false".equalsIgnoreCase(trimmed)) {
                    throw new NumberFormatException("boolean fallback requires 'true' or 'false'");
                }
                return Boolean.parseBoolean(trimmed);
            }
            if (returnType == short.class || returnType == Short.class) {
                return Short.parseShort(fallbackValue.trim());
            }
            if (returnType == byte.class || returnType == Byte.class) {
                return Byte.parseByte(fallbackValue.trim());
            }
            if (returnType == char.class || returnType == Character.class) {
                String trimmedChar = fallbackValue.trim();
                if (trimmedChar.length() != 1) {
                    throw new NumberFormatException("char fallback requires single-char value");
                }
                return trimmedChar.charAt(0);
            }
        } catch (NumberFormatException e) {
            throw new LingInvocationException(ctx.getServiceFQSID(),
                    LingInvocationException.ErrorKind.INVOKE_ERROR,
                    new IllegalArgumentException("fallbackValue '" + fallbackValue
                            + "' cannot be converted to return type " + returnType.getSimpleName(), e));
        }
        log.warn("[TerminalInvoker] fallbackValue for {} has unsupported return type {}; "
                + "rejecting instead of returning String that would ClassCastException",
                ctx.getServiceFQSID(), returnType.getSimpleName());
        throw new LingInvocationException(ctx.getServiceFQSID(),
                LingInvocationException.ErrorKind.INVOKE_ERROR,
                new IllegalArgumentException("fallbackValue not supported for return type "
                        + returnType.getSimpleName()));
    }

    private boolean shouldRetry(Throwable throwable) {
        if (!(throwable instanceof LingInvocationException)) {
            return true;
        }
        LingInvocationException invocationException = (LingInvocationException) throwable;
        return invocationException.getKind() == LingInvocationException.ErrorKind.INVOKE_ERROR
                || invocationException.getKind() == LingInvocationException.ErrorKind.INTERNAL_ERROR;
    }

    private MethodHandle resolveMethodHandle(InvocationContext ctx, Object serviceBean, Class<?>[] resolvedTypes, String cacheKey) {
        log.debug("[TerminalInvoker] Cache miss, resolving MethodHandle for {}", cacheKey);
        try {
            // 先用普通反射拿 Method，再转成 MethodHandle，兼顾可读性和热路径性能
            Method method = serviceBean.getClass().getMethod(ctx.getMethodName(), resolvedTypes);
            MethodHandle handle = MethodHandles.publicLookup().unreflect(method);
            log.trace("[TerminalInvoker] Resolved MethodHandle for {}", cacheKey);
            return handle;
        } catch (Exception e) {
            log.error("[TerminalInvoker] Failed to resolve MethodHandle for {}", cacheKey, e);
            throw new LingInvocationException(ctx.getServiceFQSID(),
                    LingInvocationException.ErrorKind.INVOKE_ERROR, e);
        }
    }

    private Object[] concatArgs(Object instance, Object[] args) {
        if (args == null || args.length == 0) {
            return new Object[] { instance };
        }
        Object[] fullArgs = new Object[args.length + 1];
        fullArgs[0] = instance;
        System.arraycopy(args, 0, fullArgs, 1, args.length);
        return fullArgs;
    }

    /**
     * 注意（架构说明）：
     * 这里每次都从目标实例容器动态取真实 Bean，并配合 {@link InvokableMethodCache} 构建句柄。
     * 这不是临时写法，而是故意的防御性设计。
     * 如果核心层在启动或扫描阶段就强引用实现类 / Bean 实例，那么灵元一旦需要卸载，
     * 核心层会因为这条引用链把目标 ClassLoader 一并挂住，最终导致“看起来卸载完成，实际上内存永远不回收”。
     * 所以：动态取壳、即时解析、缓存句柄，但不缓存实现 Bean，才是单进程可卸载架构的正确姿势。
     */
    private Object getServiceBean(LingInstance instance, InvocationContext ctx) {
        if (instance == null || instance.getContainer() == null) {
            log.trace("[TerminalInvoker] Target instance or container is missing for {}", ctx.getServiceFQSID());
            return null;
        }

        String className = ctx.resolution().getTargetClassName();
        ClassLoader classLoader = instance.getClassLoader();
        if (classLoader == null) {
            return null;
        }

        if (className != null) {
            try {
                Class<?> targetClass = classLoader.loadClass(className);
                return instance.getContainer().getBean(targetClass);
            } catch (Exception e) {
                log.warn("[TerminalInvoker] Failed to get bean by class name {}", className, e);
            }
        }

        // 裸 contractId（无 ':' 分隔）兜底：与同方法上方 colonIdx 兜底语义对齐，
        // 否则 split 返回单元素数组，[1] 直接抛 ArrayIndexOutOfBoundsException
        String fqsid = ctx.getServiceFQSID();
        int colonIdx = fqsid != null ? fqsid.indexOf(':') : -1;
        String serviceName = (colonIdx > 0 && colonIdx < fqsid.length() - 1)
                ? fqsid.substring(colonIdx + 1)
                : fqsid;
        if (serviceName != null && serviceName.contains("#")) {
            serviceName = serviceName.split("#", 2)[0];
        }

        try {
            Class<?> targetClass = classLoader.loadClass(serviceName);
            return instance.getContainer().getBean(targetClass);
        } catch (ClassNotFoundException e) {
            // 兜底按 BeanName 取，兼容部分 Spring 命名式暴露场景
            try {
                return instance.getContainer().getBean(serviceName);
            } catch (Exception inner) {
                log.trace("[TerminalInvoker] Failed to resolve bean {}", serviceName, inner);
                return null;
            }
        } catch (Exception e) {
            return null;
        }
    }
}
