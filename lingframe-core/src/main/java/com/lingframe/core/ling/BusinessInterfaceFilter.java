package com.lingframe.core.ling;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 业务接口判定收敛到 core。
 * <p>
 * 用于灵元 Bean 扫描时识别「业务契约接口」——灵元对外暴露的能力契约，
 * 排除 JDK/Spring/灵珑自身内部接口。
 * <p>
 * 委域边界：core 只持 JDK 与灵珑自身排除前缀；生态环境（Spring/Jackson/Micrometer 等）
 * 前缀由适配层通过 {@link #builder()} 传入 {@code ecosystemExcluded}。
 * 这样 core 不依赖任何生态环境，符合模块边界约束。
 */
public final class BusinessInterfaceFilter {

    // core 内置排除：JDK 与灵珑自身 API/适配层契约
    private static final Set<String> CORE_EXCLUDED_PREFIXES = Collections.unmodifiableSet(
            new LinkedHashSet<>(Arrays.asList(
                    "java.",
                    "javax.",
                    "jakarta.",
                    "com.lingframe.api.context.",
                    "com.lingframe.api.ling.",
                    "com.lingframe.api.security.",
                    "com.lingframe.api.event.",
                    "com.lingframe.core.",
                    "lombok.",
                    "org.slf4j."
            )));

    private final Set<String> excludedPrefixes;

    private BusinessInterfaceFilter(Set<String> excludedPrefixes) {
        this.excludedPrefixes = excludedPrefixes;
    }

    /**
     * 判断是否为业务接口。
     *
     * @param iface 待判接口
     * @return true 表示是业务接口，应参与灵元服务暴露
     */
    public boolean isBusinessInterface(Class<?> iface) {
        if (iface == null) {
            return false;
        }
        String name = iface.getName();
        for (String prefix : excludedPrefixes) {
            if (name.startsWith(prefix)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 创建 builder。
     *
     * @return builder 实例
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 默认实例：仅持 core 内置排除前缀，不含生态环境前缀。
     * 用于 native 路径或单测——生态环境前缀由适配层补充。
     */
    public static BusinessInterfaceFilter coreDefaults() {
        return builder().build();
    }

    public static final class Builder {
        private final Set<String> prefixes = new LinkedHashSet<>(CORE_EXCLUDED_PREFIXES);

        /**
         * 追加生态环境排除前缀。
         *
         * @param ecosystemExcluded 生态环境排除前缀集合（如 Spring/Jackson/Micrometer）
         * @return this
         */
        public Builder ecosystemExcluded(Collection<String> ecosystemExcluded) {
            if (ecosystemExcluded != null) {
                this.prefixes.addAll(ecosystemExcluded);
            }
            return this;
        }

        /**
         * 追加用户自定义排除前缀。
         *
         * @param userExcluded 用户配置的排除前缀
         * @return this
         */
        public Builder userExcluded(Collection<String> userExcluded) {
            if (userExcluded != null) {
                this.prefixes.addAll(userExcluded);
            }
            return this;
        }

        /**
         * 清空 core 内置排除前缀。
         * <p>
         * 边界：core 默认排除 JDK + 灵珑自身前缀。测试或特殊场景需要把
         * 测试嵌套接口（包名落在 com.lingframe.core.* 下）当业务接口判定时，
         * 用此方法清空默认前缀后只追加需要的排除项。
         * 生产代码不应调用——清空 core 默认会让灵珑自身接口被误当业务接口。
         *
         * @return this
         */
        public Builder clearCoreDefaults() {
            this.prefixes.clear();
            return this;
        }

        public BusinessInterfaceFilter build() {
            return new BusinessInterfaceFilter(Collections.unmodifiableSet(new LinkedHashSet<>(prefixes)));
        }
    }
}
