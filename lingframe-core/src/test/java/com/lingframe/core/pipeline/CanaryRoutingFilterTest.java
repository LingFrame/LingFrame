package com.lingframe.core.pipeline;

import com.lingframe.core.exception.LingInvocationException;
import com.lingframe.core.ling.LingInstance;
import com.lingframe.core.ling.LingRepository;
import com.lingframe.core.ling.LingRuntime;
import com.lingframe.core.spi.LingFilterChain;
import com.lingframe.core.spi.TrafficRouter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CanaryRoutingFilterTest {

    @Mock
    private LingRepository lingRepository;

    @Mock
    private TrafficRouter trafficRouter;

    @Mock
    private LingFilterChain filterChain;

    @Mock
    private LingRuntime lingRuntime;

    @Mock
    private LingInstance targetInstance;

    @InjectMocks
    private CanaryRoutingFilter filter;

    private InvocationContext context;

    @BeforeEach
    void setUp() {
        context = InvocationContext.obtain();
    }

    @AfterEach
    void tearDown() {
        context.reset();
    }

    @Test
    void doFilter_WhenTargetAlreadySet_ShouldPassThrough() throws Throwable {
        context.getAttachments().put("ling.target.instance", targetInstance);

        Object expectedResult = new Object();
        when(filterChain.doFilter(context)).thenReturn(expectedResult);

        Object result = filter.doFilter(context, filterChain);

        assertEquals(expectedResult, result);
        verify(filterChain).doFilter(context);
        verifyNoInteractions(lingRepository);
        verifyNoInteractions(trafficRouter);
    }

    @Test
    void doFilter_WhenFqsidIsNull_ShouldPassThrough() throws Throwable {
        context.setServiceFQSID(null);
        Object expectedResult = new Object();
        when(filterChain.doFilter(context)).thenReturn(expectedResult);

        Object result = filter.doFilter(context, filterChain);

        assertEquals(expectedResult, result);
        verify(filterChain).doFilter(context);
    }

    @Test
    void doFilter_WhenRuntimeNotFound_ShouldThrowRouteFailure() {
        context.setServiceFQSID("demo-ling:com.example.DemoService");
        when(lingRepository.getRuntime("demo-ling")).thenReturn(null);

        LingInvocationException ex = assertThrows(LingInvocationException.class, () -> {
            filter.doFilter(context, filterChain);
        });

        assertEquals(LingInvocationException.ErrorKind.ROUTE_FAILURE, ex.getKind());
        verifyNoInteractions(filterChain);
    }

    @Test
    void doFilter_WhenNoReadyInstances_ShouldThrowRouteFailure() {
        context.setServiceFQSID("demo-ling:com.example.DemoService");
        when(lingRepository.getRuntime("demo-ling")).thenReturn(lingRuntime);
        when(lingRuntime.getReadyInstances()).thenReturn(Collections.emptyList());

        LingInvocationException ex = assertThrows(LingInvocationException.class, () -> {
            filter.doFilter(context, filterChain);
        });

        assertEquals(LingInvocationException.ErrorKind.ROUTE_FAILURE, ex.getKind());
        verifyNoInteractions(trafficRouter);
    }

    @Test
    void doFilter_WhenRouterReturnsNull_ShouldThrowRouteFailure() {
        context.setServiceFQSID("demo-ling:com.example.DemoService");
        when(lingRepository.getRuntime("demo-ling")).thenReturn(lingRuntime);
        when(lingRuntime.getReadyInstances()).thenReturn(Collections.singletonList(targetInstance));
        when(trafficRouter.route(any(), eq(context))).thenReturn(null);

        LingInvocationException ex = assertThrows(LingInvocationException.class, () -> {
            filter.doFilter(context, filterChain);
        });

        assertEquals(LingInvocationException.ErrorKind.ROUTE_FAILURE, ex.getKind());
    }

    @Test
    void doFilter_WhenRouterReturnsTarget_ShouldSetContextAndPassThrough() throws Throwable {
        context.setServiceFQSID("demo-ling:com.example.DemoService");
        when(lingRepository.getRuntime("demo-ling")).thenReturn(lingRuntime);
        when(lingRuntime.getReadyInstances()).thenReturn(Arrays.asList(targetInstance));
        when(trafficRouter.route(any(), eq(context))).thenReturn(targetInstance);

        when(targetInstance.getLingId()).thenReturn("demo-ling");
        when(targetInstance.getVersion()).thenReturn("1.0.0");

        Object expectedResult = new Object();
        when(filterChain.doFilter(context)).thenReturn(expectedResult);

        Object result = filter.doFilter(context, filterChain);

        assertEquals(expectedResult, result);
        assertEquals("demo-ling", context.getTargetLingId());
        assertEquals("1.0.0", context.getTargetVersion());
        assertEquals(targetInstance, context.getAttachments().get("ling.target.instance"));

        verify(filterChain).doFilter(context);
    }
}
