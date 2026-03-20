package com.lingframe.core.ling;

import com.lingframe.api.config.LingDefinition;
import com.lingframe.core.event.EventBus;
import com.lingframe.core.invoker.DefaultLingServiceInvoker;
import com.lingframe.core.pipeline.InvocationContext;
import com.lingframe.core.spi.LingContainer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("DefaultLingServiceInvoker 测试")
class DefaultLingServiceInvokerTest {

    @Test
    @DisplayName("方法执行期间应登记活跃调用事实")
    void shouldRegisterActiveInvocationFactsDuringExecution() throws Exception {
        LingContainer container = mock(LingContainer.class);
        when(container.isActive()).thenReturn(true);
        when(container.getClassLoader()).thenReturn(getClass().getClassLoader());

        LingDefinition definition = new LingDefinition();
        definition.setId("test-ling");
        definition.setVersion("1.0.0");

        LingInstance instance = new LingInstance(container, definition, new EventBus());
        InstanceCoordinator coordinator = new InstanceCoordinator(null);
        coordinator.prepare(instance);
        coordinator.start(instance);
        coordinator.markReady(instance);

        ProbeService service = new ProbeService(instance);
        Method method = ProbeService.class.getMethod("execute");

        InvocationContext ctx = InvocationContext.obtain();
        ctx.setTraceId("trace-ctx");
        ctx.setCallerLingId("caller-a");
        ctx.setServiceFQSID("test-ling:demo.Service");
        ctx.setMethodName("execute");
        ctx.setResourceId("POST /demo");
        InvocationContext previous = ctx.attach();

        try {
            Object result = new DefaultLingServiceInvoker().invoke(instance, service, method, new Object[0]);
            assertEquals("ok", result);
        } finally {
            InvocationContext.detach(previous);
            ctx.recycle();
        }

        assertEquals(1, service.observedSnapshots.size());
        ActiveInvocationSnapshot snapshot = service.observedSnapshots.get(0);
        assertEquals("trace-ctx", snapshot.getTraceId());
        assertEquals("test-ling:demo.Service", snapshot.getServiceFQSID());
        assertEquals("execute", snapshot.getMethodName());
        assertEquals("caller-a", snapshot.getCallerLingId());
        assertEquals("POST /demo", snapshot.getResourceId());
        assertTrue(instance.snapshotActiveInvocations().isEmpty());
        assertEquals(0, instance.getActiveRequestCount());
    }

    public static class ProbeService {
        private final LingInstance instance;
        private List<ActiveInvocationSnapshot> observedSnapshots;

        public ProbeService(LingInstance instance) {
            this.instance = instance;
        }

        public String execute() {
            observedSnapshots = instance.snapshotActiveInvocations();
            return "ok";
        }
    }
}
