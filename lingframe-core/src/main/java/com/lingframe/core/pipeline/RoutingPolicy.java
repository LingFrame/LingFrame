package com.lingframe.core.pipeline;

import com.lingframe.core.kernel.InvocationContext;
import com.lingframe.core.ling.LingInstance;
import java.util.List;

public interface RoutingPolicy {
    LingInstance select(InvocationContext ctx, List<LingInstance> candidates);
}
