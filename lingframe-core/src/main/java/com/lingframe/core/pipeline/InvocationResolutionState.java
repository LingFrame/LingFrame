package com.lingframe.core.pipeline;

import lombok.Getter;
import lombok.Setter;

import java.lang.reflect.Method;

/**
 * 解析阶段协议分区。
 * ⚠️ 这里允许持有 ClassLoader / Class<?>[] / Method 这类强引用，
 * 但它们只允许在“当前调用正在进行”这段时间内存在，绝不能跨调用残留。
 */
@Getter
@Setter
public class InvocationResolutionState {

    /**
     * 目标服务类名。
     */
    private String targetClassName;

    /**
     * 目标类加载器。
     * 解析和执行前会临时切到该 TCCL 视角，保证看到的是目标灵元自己的类型宇宙。
     */
    private ClassLoader targetClassLoader;

    /**
     * 已解析的参数类型。
     * 解析完成后不再重复根据字符串反查 Class，减少热路径开销。
     */
    private Class<?>[] resolvedParameterTypes;

    /**
     * 已解析的目标方法。
     * 只作为当前调用的阶段产物，不允许被外部长期缓存。
     */
    private Method resolvedMethod;

    void reset() {
        this.targetClassName = null;
        this.targetClassLoader = null;
        this.resolvedParameterTypes = null;
        this.resolvedMethod = null;
    }

    void copyFrom(InvocationResolutionState source) {
        if (source == null) {
            return;
        }
        this.targetClassName = source.targetClassName;
        this.targetClassLoader = source.targetClassLoader;
        this.resolvedParameterTypes = source.resolvedParameterTypes;
        this.resolvedMethod = source.resolvedMethod;
    }
}
