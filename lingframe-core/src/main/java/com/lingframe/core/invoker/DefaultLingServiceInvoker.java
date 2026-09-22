package com.lingframe.core.invoker;

import com.lingframe.core.ling.LingInstance;
import com.lingframe.core.spi.LingServiceInvoker;
import com.lingframe.api.exception.LingInvocationException;
import com.lingframe.api.exception.InvalidArgumentException;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

@Slf4j
public class DefaultLingServiceInvoker implements LingServiceInvoker {

    @Override
    public Object invoke(LingInstance instance, Object bean, Method method, Object[] args) throws Exception {
        // 引用计数保护
        long invocationId = instance.beginInvocation(ActiveInvocationSupport.capture(instance, method.getName()));
        if (invocationId < 0) {
            throw new LingInvocationException(instance.getLingId(),
                    LingInvocationException.ErrorKind.STATE_REJECTED,
                    "Ling instance is not ready or already destroyed");
        }
        ClassLoader originalClassLoader = Thread.currentThread().getContextClassLoader();
        ClassLoader targetClassLoader = instance.getClassLoader();
        // force-drain / tearDown 时 CL 可能已关闭或清空：给出确定性错误，避免难诊断 NPE
        if (targetClassLoader == null) {
            instance.completeInvocation(invocationId);
            throw new LingInvocationException(instance.getLingId(),
                    LingInvocationException.ErrorKind.STATE_REJECTED,
                    "Ling instance classloader is unavailable (likely unloaded or force-drained): "
                            + instance.getInstanceId());
        }

        Object[] finalArgs = args;
        try {
            // 切换线程上下文类加载器（TCCL）
            Thread.currentThread().setContextClassLoader(targetClassLoader);

            // 生产级自适应入参转换（阻抗失配自适应绑定，零强引用滞留）
            finalArgs = ArgumentTypeAdapter.adapt(method, args, targetClassLoader);

            // 反射调用
            return method.invoke(bean, finalArgs);

        } catch (IllegalArgumentException e) {
            // 捕获参数异常，进行详细分析并重新抛出。
            // handleArgumentMismatch 在参数数量/类型不匹配时抛出带完整诊断的 InvalidArgumentException；
            // 若其未判定出 mismatch（isCompatible 与反射校验的边界差异等理论场景），
            // 此处兜底抛出原始异常，避免静默吞掉失败——两条路径都保证调用方拿到失败信息。
            handleArgumentMismatch(method, finalArgs, e);
            throw e;
        } catch (InvocationTargetException e) {
            // 透传业务异常
            Throwable target = e.getTargetException();
            if (target instanceof Exception)
                throw (Exception) target;
            throw new LingInvocationException(instance.getLingId(), LingInvocationException.ErrorKind.INTERNAL_ERROR, target);
        } finally {
            // 资源恢复
            Thread.currentThread().setContextClassLoader(originalClassLoader);
            instance.completeInvocation(invocationId);
        }
    }

    /**
     * 专门用于分析参数不匹配的方法
     */
    private void handleArgumentMismatch(Method method, Object[] args, IllegalArgumentException e) {
        Class<?>[] parameterTypes = method.getParameterTypes();

        // 检查参数数量
        if (args == null)
            args = new Object[0];
        if (args.length != parameterTypes.length) {
            throw new InvalidArgumentException("args", String.format(
                    "调用失败：参数数量不匹配。方法 [%s] 需要 %d 个参数，实际传入 %d 个。",
                    method.getName(), parameterTypes.length, args.length), e);
        }

        // 逐个检查参数类型
        StringBuilder errorReport = new StringBuilder();
        boolean foundMismatch = false;

        for (int i = 0; i < parameterTypes.length; i++) {
            Class<?> expectedType = parameterTypes[i];
            Object actualArg = args[i];

            // 检查类型是否兼容(注意处理基本类型和包装类的自动装箱逻辑)
            if (!isCompatible(expectedType, actualArg)) {
                foundMismatch = true;
                String hint = analyzeMismatchCause(expectedType, actualArg);

                errorReport.append("\n")
                        .append("  👉 第 ").append(i + 1).append(" 个参数不匹配:\n")
                        .append("\n")
                        .append("     - 期望类型: ").append(expectedType.getSimpleName()).append("\n")
                        .append("\n")
                        .append("     - 实际类型: ")
                        .append(actualArg == null ? "null" : actualArg.getClass().getSimpleName()).append("\n")
                        .append("\n")
                        .append("     - 实际传入: ").append(actualArg).append("\n")
                        .append("\n")
                        .append("     - 诊断提示: ").append(hint).append("\n");
            }
        }

        if (foundMismatch) {
            throw new InvalidArgumentException("args", String.format(
                    "调用灵元服务 [%s] 失败，参数类型不匹配：%s",
                    method.getName(), errorReport), e);
        }
    }

    /**
     * 💡 核心逻辑：分析不匹配的具体原因
     */
    private String analyzeMismatchCause(Class<?> expected, Object actual) {
        // 情况 A: 传了 null 给基本类型(int, boolean, double...)
        if (actual == null) {
            if (expected.isPrimitive()) {
                return "基本数据类型 [" + expected.getSimpleName() + "] 不能接受 null 值";
            }
            return "类型不兼容";
        }

        Class<?> actualType = actual.getClass();

        // 情况 B: 数字类型不匹配(最常见)
        if (Number.class.isAssignableFrom(wrap(expected)) && Number.class.isAssignableFrom(actualType)) {
            return "数字类型精度不一致 (请检查 Integer/Long/Double 混用)";
        }

        // 情况 C: 类名完全一样，但是不匹配？ -> 肯定是类加载器问题！
        // 这在 LingFrame 这种灵元框架中非常关键！
        if (expected.getName().equals(actualType.getName())) {
            return String.format(
                    "🔥 类加载器冲突！目标类由 [%s] 加载，但传入对象由 [%s] 加载。",
                    getClassLoaderName(expected),
                    getClassLoaderName(actualType));
        }

        // 情况 D: 完全风马牛不相及
        return "类型完全不兼容，请检查传参顺序或对象类型";
    }

    // 获取类加载器名称的辅助方法
    private String getClassLoaderName(Class<?> clazz) {
        ClassLoader cl = clazz.getClassLoader();
        return (cl != null) ? cl.toString() : "Bootstrap ClassLoader";
    }

    /**
     * 辅助方法：判断类型是否兼容（处理基本类型的装箱拆箱）
     */
    private boolean isCompatible(Class<?> expected, Object actual) {
        if (actual == null) {
            // 基本类型不能为 null
            return !expected.isPrimitive();
        }
        // 允许自动装箱/拆箱的判断
        return wrap(expected).isAssignableFrom(actual.getClass());
    }

    private Class<?> wrap(Class<?> type) {
        if (!type.isPrimitive())
            return type;
        if (type == int.class)
            return Integer.class;
        if (type == long.class)
            return Long.class;
        if (type == double.class)
            return Double.class;
        if (type == float.class)
            return Float.class;
        if (type == boolean.class)
            return Boolean.class;
        if (type == byte.class)
            return Byte.class;
        if (type == short.class)
            return Short.class;
        if (type == char.class)
            return Character.class;
        return type;
    }
}
