package com.lingframe.starter.filter;

import com.lingframe.api.context.LingCallContext;
import com.lingframe.api.exception.LingInvocationException;
import com.lingframe.core.ling.LingInstance;
import com.lingframe.core.metrics.LingHealthMetrics;
import com.lingframe.core.metrics.MetricsCollector;
import com.lingframe.core.pipeline.InvocationContext;
import com.lingframe.core.pipeline.InvocationExecutionMode;
import com.lingframe.core.pipeline.InvocationPipelineEngine;
import com.lingframe.starter.config.LingFrameProperties;
import com.lingframe.starter.governance.EntryInvocationGovernanceResolver;
import com.lingframe.starter.web.WebInterfaceMetadata;
import com.lingframe.starter.web.WebGovernanceSupport;
import com.lingframe.starter.web.WebRequestFacade;
import com.lingframe.starter.web.WebRouteResolution;
import com.lingframe.starter.web.WebRouteResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerExecutionChain;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.lang.reflect.Method;

@Slf4j
@RequiredArgsConstructor
public class LingWebGovernanceFilter extends OncePerRequestFilter {

    private static final String LING_CORE_ID = "lingcore-app";
    private static final WebGovernanceSupport GOVERNANCE_SUPPORT = new WebGovernanceSupport();

    private final WebRouteResolver webRouteResolver;
    private final InvocationPipelineEngine pipelineEngine;
    private final LingFrameProperties properties;
    private final RequestMappingHandlerMapping requestMappingHandlerMapping;
    private final ObjectProvider<MetricsCollector> metricsCollectorProvider;
    private final EntryInvocationGovernanceResolver invocationGovernanceResolver;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain)
            throws ServletException, IOException {

        WebRouteResolution lingRoute = webRouteResolver.resolveRoute(request);
        WebInterfaceMetadata lingMeta = lingRoute != null ? lingRoute.getMetadata() : null;
        boolean isLingRequest = lingMeta != null;
        WebRequestFacade requestFacade = adaptRequest(request);

        HandlerMethod handlerMethod = null;
        if (!isLingRequest) {
            handlerMethod = resolveHandlerMethod(request);
            if (handlerMethod == null) {
                filterChain.doFilter(request, response);
                return;
            }
        }

        if (!isLingRequest && !properties.getLingCoreGovernance().isEnabled()) {
            filterChain.doFilter(request, response);
            return;
        }

        String lingId = isLingRequest ? lingMeta.getLingId() : LING_CORE_ID;
        ClassLoader originalCL = null;
        if (isLingRequest) {
            originalCL = Thread.currentThread().getContextClassLoader();
            Thread.currentThread().setContextClassLoader(lingMeta.getClassLoader());
        }

        LingCallContext.setLingId(lingId);

        InvocationContext ctx = null;
        long startNanos = System.nanoTime();
        Throwable downstreamError = null;
        try {
            try {
                Method method = GOVERNANCE_SUPPORT.resolveGovernedMethod(isLingRequest, lingMeta, handlerMethod, lingId);
                ctx = GOVERNANCE_SUPPORT.buildInvocationContext(
                        requestFacade, method, lingId, lingMeta, invocationGovernanceResolver);
                ctx.setExecutionMode(InvocationExecutionMode.GOVERN_ONLY);
                if (lingRoute != null) {
                    GOVERNANCE_SUPPORT.preResolveLingTarget(ctx, lingRoute);
                }
                pipelineEngine.invoke(ctx);
                LingInstance routed = ctx.routing().getTargetInstance();
                if (routed != null) {
                    request.setAttribute("ling.target.version", routed.getVersion());
                }
            } catch (LingInvocationException e) {
                if (e.getKind() == LingInvocationException.ErrorKind.SECURITY_REJECTED) {
                    log.warn("[Governance] Security rejected (SB2): {} -> {}",
                            GOVERNANCE_SUPPORT.resolveGovernanceResourceId(ctx, requestFacade), e.getMessage());
                } else {
                    log.info("[Governance] Request blocked (SB2): {} -> {}",
                            GOVERNANCE_SUPPORT.resolveGovernanceResourceId(ctx, requestFacade), e.getMessage());
                }
                handleGovernanceFailure(response, e, ctx);
                return;
            }

            filterChain.doFilter(request, response);
        } catch (Throwable t) {
            downstreamError = t;
            throw t;
        } finally {
            recordWebMetrics(request, response, ctx, lingId, isLingRequest, startNanos, downstreamError);
            if (ctx != null) {
                ctx.recycle();
            }
            if (originalCL != null) {
                Thread.currentThread().setContextClassLoader(originalCL);
            }
            LingCallContext.clear();
        }
    }

    private WebRequestFacade adaptRequest(HttpServletRequest request) {
        return new ServletWebRequestFacade(request);
    }

    private void handleGovernanceFailure(HttpServletResponse response,
            LingInvocationException e,
            InvocationContext ctx) throws IOException {
        if (e.getKind() == LingInvocationException.ErrorKind.SECURITY_REJECTED && ctx != null) {
            response.sendError(403, "Permission Denied: " + ctx.getRequiredPermission());
        } else if (e.getKind() == LingInvocationException.ErrorKind.STATE_REJECTED
                || e.getKind() == LingInvocationException.ErrorKind.ROUTE_FAILURE) {
            response.sendError(503, e.getMessage());
        } else {
            response.sendError(500, "Governance Error: " + e.getKind());
        }
    }

    private HandlerMethod resolveHandlerMethod(HttpServletRequest request) {
        try {
            HandlerExecutionChain chain = requestMappingHandlerMapping.getHandler(request);
            if (chain != null && chain.getHandler() instanceof HandlerMethod) {
                return (HandlerMethod) chain.getHandler();
            }
        } catch (Exception e) {
            log.debug("Failed to resolve handler for {}: {}", request.getRequestURI(), e.getMessage());
        }
        return null;
    }

    private void recordWebMetrics(HttpServletRequest request,
            HttpServletResponse response,
            InvocationContext ctx,
            String lingId,
            boolean isLingRequest,
            long startNanos,
            Throwable error) {
        if (!isLingRequest && !LING_CORE_ID.equals(lingId)) {
            return;
        }

        MetricsCollector metricsCollector = metricsCollectorProvider != null ? metricsCollectorProvider.getIfAvailable() : null;
        if (metricsCollector == null || lingId == null || lingId.isEmpty()) {
            return;
        }

        long costMs = (System.nanoTime() - startNanos) / 1_000_000;
        String version = resolveVersion(request, ctx);
        LingHealthMetrics metrics = metricsCollector.getOrCreate(lingId);
        LingHealthMetrics versionMetrics = metricsCollector.getOrCreate(lingId, version);

        boolean success = error == null && response.getStatus() < 500;
        if (success) {
            metrics.recordSuccess(costMs);
            if (versionMetrics != metrics) {
                versionMetrics.recordSuccess(costMs);
            }
            return;
        }

        boolean isTimeout = response.getStatus() == HttpServletResponse.SC_GATEWAY_TIMEOUT || isTimeoutError(error);
        metrics.recordFailure(costMs, isTimeout);
        if (versionMetrics != metrics) {
            versionMetrics.recordFailure(costMs, isTimeout);
        }
    }

    private String resolveVersion(HttpServletRequest request, InvocationContext ctx) {
        Object versionAttr = request.getAttribute("ling.target.version");
        if (versionAttr instanceof String && !((String) versionAttr).isEmpty()) {
            return (String) versionAttr;
        }
        return ctx != null ? ctx.getTargetVersion() : null;
    }

    private boolean isTimeoutError(Throwable error) {
        if (error == null) {
            return false;
        }
        String message = error.getMessage();
        if (message != null) {
            String lower = message.toLowerCase();
            if (lower.contains("timeout") || lower.contains("timed out")) {
                return true;
            }
        }
        return isTimeoutError(error.getCause());
    }

    private static final class ServletWebRequestFacade implements WebRequestFacade {
        private final HttpServletRequest request;

        private ServletWebRequestFacade(HttpServletRequest request) {
            this.request = request;
        }

        @Override
        public String getMethod() {
            return request.getMethod();
        }

        @Override
        public String getRequestURI() {
            return request.getRequestURI();
        }

        @Override
        public String getHeader(String name) {
            return request.getHeader(name);
        }

        @Override
        public java.security.Principal getUserPrincipal() {
            return request.getUserPrincipal();
        }

        @Override
        public String getRemoteUser() {
            return request.getRemoteUser();
        }
    }
}
