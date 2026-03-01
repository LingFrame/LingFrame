package com.lingframe.core.pipeline;

import com.lingframe.core.fsm.RuntimeStatus;
import com.lingframe.core.kernel.InvocationContext;
import com.lingframe.core.kernel.LingInvocationException;
import com.lingframe.core.ling.LingRepository;
import com.lingframe.core.ling.LingRuntime;
import com.lingframe.core.spi.LingFilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MacroStateGuardFilterTest {

    @Mock
    private LingRepository lingRepository;

    @Mock
    private LingRuntime lingRuntime;

    @Mock
    private LingFilterChain filterChain;

    @InjectMocks
    private MacroStateGuardFilter filter;

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
    void doFilter_WhenFqsidIsNull_ShouldPassThrough() throws Throwable {
        context.setServiceFQSID(null);
        Object expectedResult = new Object();
        when(filterChain.doFilter(context)).thenReturn(expectedResult);

        Object result = filter.doFilter(context, filterChain);

        assertEquals(expectedResult, result);
        verify(filterChain).doFilter(context);
        verifyNoInteractions(lingRepository);
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
    void doFilter_WhenStatusIsActive_ShouldPassThrough() throws Throwable {
        context.setServiceFQSID("demo-ling:com.example.DemoService");
        when(lingRepository.getRuntime("demo-ling")).thenReturn(lingRuntime);
        when(lingRuntime.currentStatus()).thenReturn(RuntimeStatus.ACTIVE);

        Object expectedResult = new Object();
        when(filterChain.doFilter(context)).thenReturn(expectedResult);

        Object result = filter.doFilter(context, filterChain);

        assertEquals(expectedResult, result);
        verify(filterChain).doFilter(context);
    }

    @Test
    void doFilter_WhenStatusIsDegraded_ShouldPassThrough() throws Throwable {
        context.setServiceFQSID("demo-ling:com.example.DemoService");
        when(lingRepository.getRuntime("demo-ling")).thenReturn(lingRuntime);
        when(lingRuntime.currentStatus()).thenReturn(RuntimeStatus.DEGRADED);

        Object expectedResult = new Object();
        when(filterChain.doFilter(context)).thenReturn(expectedResult);

        Object result = filter.doFilter(context, filterChain);

        assertEquals(expectedResult, result);
        verify(filterChain).doFilter(context);
    }

    @Test
    void doFilter_WhenStatusIsInactive_ShouldThrowRouteFailure() {
        context.setServiceFQSID("demo-ling:com.example.DemoService");
        when(lingRepository.getRuntime("demo-ling")).thenReturn(lingRuntime);
        when(lingRuntime.currentStatus()).thenReturn(RuntimeStatus.INACTIVE);

        LingInvocationException ex = assertThrows(LingInvocationException.class, () -> {
            filter.doFilter(context, filterChain);
        });

        assertEquals(LingInvocationException.ErrorKind.ROUTE_FAILURE, ex.getKind());
        verifyNoInteractions(filterChain);
    }

    @Test
    void doFilter_WhenStatusIsStopping_ShouldThrowStateRejected() {
        context.setServiceFQSID("demo-ling:com.example.DemoService");
        when(lingRepository.getRuntime("demo-ling")).thenReturn(lingRuntime);
        when(lingRuntime.currentStatus()).thenReturn(RuntimeStatus.STOPPING);

        LingInvocationException ex = assertThrows(LingInvocationException.class, () -> {
            filter.doFilter(context, filterChain);
        });

        assertEquals(LingInvocationException.ErrorKind.STATE_REJECTED, ex.getKind());
        verifyNoInteractions(filterChain);
    }

    @Test
    void doFilter_WhenStatusIsRemoved_ShouldThrowRouteFailure() {
        context.setServiceFQSID("demo-ling:com.example.DemoService");
        when(lingRepository.getRuntime("demo-ling")).thenReturn(lingRuntime);
        when(lingRuntime.currentStatus()).thenReturn(RuntimeStatus.REMOVED);

        LingInvocationException ex = assertThrows(LingInvocationException.class, () -> {
            filter.doFilter(context, filterChain);
        });

        assertEquals(LingInvocationException.ErrorKind.ROUTE_FAILURE, ex.getKind());
        verifyNoInteractions(filterChain);
    }
}
