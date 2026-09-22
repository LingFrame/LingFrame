package com.lingframe.starter.resource;

import com.lingframe.core.config.LingFrameConfig;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.TestExecutionListener;

/**
 * 渲染测试环境下的 {@link LingFrameConfig} 静态单例重置监听器。
 * <p>
 * 背景：{@link LingFrameConfig#init} 是"恰好一次"的静态守卫，而 Spring 测试框架会按配置缓存
 * ApplicationContext，跨测试类之间前一上下文不会被立即关闭——其静态 {@code INSTANCE} 得以存续。
 * 于是后续某测试类装载新的/不同的上下文时，{@code lingFrameConfig} Bean 再触发 {@code init()} 即抛
 * "already initialized"，导致整类上下文加载失败（CI 特异、顺序敏感）。
 * <p>
 * 修法：在 {@link #beforeTestClass} 重置静态单例——Spring TestContext 契约保证该回调先于任何
 * ApplicationContext 创建执行（对 SB2/SB3 时序一致），从源头消除跨类污染，而非依赖测试执行顺序。
 * 仅用于测试，生产路径不受影响。
 */
public class LingFrameConfigResetListener implements TestExecutionListener {

    @Override
    public void beforeTestClass(TestContext testContext) {
        LingFrameConfig.clear();
    }
}