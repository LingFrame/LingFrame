package com.lingframe.api.exception;

import com.lingframe.api.security.AccessType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

@DisplayName("API Exception 统一单元测试")
class ApiExceptionTest {

    @Test
    @DisplayName("测试 Exception 各构造器")
    void testExceptions() {
        Throwable cause = new RuntimeException("root cause");

        // 1. LingException
        LingException le1 = new LingException("msg");
        assertEquals("msg", le1.getMessage());
        LingException le2 = new LingException("msg", cause);
        assertEquals("msg", le2.getMessage());
        assertSame(cause, le2.getCause());

        // 2. LingRuntimeException
        LingRuntimeException lre1 = new LingRuntimeException("ling-x", "msg");
        assertEquals("msg", lre1.getMessage());
        assertEquals("ling-x", lre1.getLingId());
        LingRuntimeException lre2 = new LingRuntimeException("ling-x", "msg", cause);
        assertEquals("msg", lre2.getMessage());
        assertSame(cause, lre2.getCause());

        // 3. InvalidArgumentException
        InvalidArgumentException iae1 = new InvalidArgumentException("msg");
        assertEquals("msg", iae1.getMessage());
        assertNull(iae1.getParamName());
        assertNull(iae1.getInvalidValue());

        InvalidArgumentException iae2 = new InvalidArgumentException("param", "msg");
        assertEquals("msg", iae2.getMessage());
        assertEquals("param", iae2.getParamName());
        assertNull(iae2.getInvalidValue());

        InvalidArgumentException iae3 = new InvalidArgumentException("param", "val", "msg");
        assertEquals("msg", iae3.getMessage());
        assertEquals("param", iae3.getParamName());
        assertEquals("val", iae3.getInvalidValue());

        InvalidArgumentException iae4 = new InvalidArgumentException("param", "msg", cause);
        assertEquals("msg", iae4.getMessage());
        assertEquals("param", iae4.getParamName());
        assertSame(cause, iae4.getCause());

        // 4. CallNotPermittedException
        CallNotPermittedException cnpe1 = new CallNotPermittedException("res-x", "reason-x");
        assertEquals("res-x", cnpe1.getResourceId());
        assertEquals("reason-x", cnpe1.getReason());
        assertNotNull(cnpe1.getMessage());

        // 5. InvocationException
        InvocationException ie1 = new InvocationException("msg");
        assertEquals("msg", ie1.getMessage());
        InvocationException ie2 = new InvocationException("msg", cause);
        assertEquals("msg", ie2.getMessage());
        assertSame(cause, ie2.getCause());

        // 6. LingInvocationException
        LingInvocationException lie1 = new LingInvocationException("ling-x:service-x", LingInvocationException.ErrorKind.INVOKE_ERROR);
        assertEquals("ling-x:service-x", lie1.getFqsid());
        assertEquals(LingInvocationException.ErrorKind.INVOKE_ERROR, lie1.getKind());
        assertEquals("LING-5001", lie1.getKind().getCode());

        LingInvocationException lie2 = new LingInvocationException("ling-x:service-x", LingInvocationException.ErrorKind.TIMEOUT, "timeout msg");
        assertEquals("ling-x:service-x", lie2.getFqsid());
        assertEquals(LingInvocationException.ErrorKind.TIMEOUT, lie2.getKind());
        assertEquals("timeout msg", lie2.getMessage());

        LingInvocationException lie3 = new LingInvocationException("ling-x:service-x", LingInvocationException.ErrorKind.INTERNAL_ERROR, cause);
        assertEquals("ling-x:service-x", lie3.getFqsid());
        assertSame(cause, lie3.getCause());

        // 7. LingNotFoundException
        LingNotFoundException lnfe1 = new LingNotFoundException("ling-x");
        assertEquals("ling-x", lnfe1.getLingId());
        
        LingNotFoundException lnfe2 = new LingNotFoundException("ling-x", "custom msg");
        assertEquals("ling-x", lnfe2.getLingId());
        assertEquals("custom msg", lnfe2.getMessage());

        // 8. PermissionDeniedException
        PermissionDeniedException pde1 = new PermissionDeniedException("msg");
        assertEquals("msg", pde1.getMessage());

        PermissionDeniedException pde2 = new PermissionDeniedException("msg", cause);
        assertSame(cause, pde2.getCause());

        PermissionDeniedException pde3 = new PermissionDeniedException("ling-x", "capability-x");
        assertEquals("ling-x", pde3.getLingId());
        assertEquals("capability-x", pde3.getCapability());
        assertNull(pde3.getAccessType());

        PermissionDeniedException pde4 = new PermissionDeniedException("ling-x", "capability-x", AccessType.WRITE);
        assertEquals("ling-x", pde4.getLingId());
        assertEquals("capability-x", pde4.getCapability());
        assertEquals(AccessType.WRITE, pde4.getAccessType());

        // 9. ServiceNotFoundException
        ServiceNotFoundException snfe1 = new ServiceNotFoundException("service-x");
        assertEquals("service-x", snfe1.getServiceName());
        assertNull(snfe1.getLingId());

        ServiceNotFoundException snfe2 = new ServiceNotFoundException("service-x", "ling-x");
        assertEquals("service-x", snfe2.getServiceName());
        assertEquals("ling-x", snfe2.getLingId());

        // 10. ServiceUnavailableException
        ServiceUnavailableException sue1 = new ServiceUnavailableException("service-x", "reason-x");
        assertEquals("service-x", sue1.getServiceName());
        assertEquals("reason-x", sue1.getReason());
    }
}
