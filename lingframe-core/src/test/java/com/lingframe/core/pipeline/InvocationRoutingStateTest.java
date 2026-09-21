package com.lingframe.core.pipeline;

import com.lingframe.core.routing.LingRoutingScope;
import com.lingframe.core.routing.LingVersionPolicy;
import com.lingframe.core.routing.ProviderWeightRouter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

/** 路由分区的不可变策略传递与池化清理测试。 */
@DisplayName("路由策略的上下文归属")
class InvocationRoutingStateTest {
    @Test
    @DisplayName("复制保留同一修订而回收断开策略引用")
    void copyAndResetPolicy() {
        ProviderWeightRouter router = new ProviderWeightRouter();
        LingRoutingScope scope = new LingRoutingScope("a", "execute");
        LingVersionPolicy policy = router.replaceLingVersionPolicy(scope,
                router.getLingVersionPolicy(scope).getRevision(), Collections.singletonMap("v1", 100));
        InvocationRoutingState source = new InvocationRoutingState();
        source.setLingVersionPolicy(policy);
        InvocationRoutingState copy = new InvocationRoutingState();
        copy.copyFrom(source);
        source.reset();
        assertNull(source.getLingVersionPolicy());
        assertSame(policy, copy.getLingVersionPolicy());
        copy.copyFrom(null);
        assertSame(policy, copy.getLingVersionPolicy());
        copy.reset();
        assertNull(copy.getLingVersionPolicy());
    }
}
