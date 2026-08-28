package com.lingframe.core.security;

import com.lingframe.api.context.LingCallContext;

/**
 * 灵核专属操作守卫（阶段 1a 铁线、双保险之第二道）。
 *
 * <p>影响性操作（unload/update/canary）只允许灵核调用，禁止处于灵元执行栈中的
 * 灵元越权触发。第一道是调用链入口校验（invokeAs 身份），本守卫为第二道：在影响性操作
 * 底层实现内部首行判定——当前线程若正处于某灵元上下文中（{@link LingCallContext} 的
 * ThreadLocal 栈标记存在），抛出 {@link SecurityException} 拒绝。
 *
 * <p>即便灵元绕过入口、直接持 registry/Bean 引用在自身执行栈内调用底层方法，也会命中
 * 此判定；该检查与调用入口无关，任何进入路径都无法绕过（new Thread 丢 ThreadLocal 的理论
 * 逃逸由第一道入口防御封堵）。
 */
public final class LingCoreOnlyGuard {

    private LingCoreOnlyGuard() {
    }

    /**
     * 断言当前线程不属于任何灵元上下文，否则抛 {@link SecurityException}。
     *
     * @param operation 影响性操作名称，用于拒绝信息定位
     */
    public static void assertLingCoreContext(String operation) {
        String lingId = LingCallContext.getLingId();
        if (lingId != null && !lingId.isEmpty()) {
            throw new SecurityException("in-ling context [" + lingId + "] is not allowed to perform "
                    + operation + "; only ling core can");
        }
    }
}
