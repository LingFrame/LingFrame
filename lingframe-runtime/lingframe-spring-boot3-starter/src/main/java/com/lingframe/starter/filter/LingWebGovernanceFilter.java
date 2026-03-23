package com.lingframe.starter.filter;

import com.lingframe.api.context.LingCallContext;
import com.lingframe.api.exception.LingInvocationException;
import com.lingframe.core.ling.LingInstance;
import com.lingframe.core.pipeline.InvocationContext;
import com.lingframe.core.pipeline.InvocationExecutionMode;
import com.lingframe.core.pipeline.InvocationPipelineEngine;
import com.lingframe.starter.config.LingFrameProperties;
import com.lingframe.starter.web.WebInterfaceMetadata;
import com.lingframe.starter.web.WebGovernanceSupport;
import com.lingframe.starter.web.WebRequestFacade;
import com.lingframe.starter.web.WebRouteResolution;
import com.lingframe.starter.web.WebRouteResolver;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerExecutionChain;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.io.IOException;
import java.lang.reflect.Method;

/**
 * 面向 Spring Boot 3.x 的统一 Web 治理过滤器。
 */
@Slf4j
@RequiredArgsConstructor
public class LingWebGovernanceFilter extends OncePerRequestFilter {

    private static final String LING_CORE_ID = "lingcore-app";
    private static final WebGovernanceSupport GOVERNANCE_SUPPORT = new WebGovernanceSupport();

    private final WebRouteResolver webRouteResolver;
    private final InvocationPipelineEngine pipelineEngine;
    private final LingFrameProperties properties;
    private final RequestMappingHandlerMapping requestMappingHandlerMapping;

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain)
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
        try {
            try {
                Method method = GOVERNANCE_SUPPORT.resolveGovernedMethod(isLingRequest, lingMeta, handlerMethod, lingId);
                ctx = GOVERNANCE_SUPPORT.buildInvocationContext(requestFacade, method, lingId, lingMeta);
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
                    log.warn("[Governance] Security rejected: {} -> {}",
                            GOVERNANCE_SUPPORT.resolveGovernanceResourceId(ctx, requestFacade), e.getMessage());
                } else {
                    log.info("[Governance] Request blocked: {} -> {}",
                            GOVERNANCE_SUPPORT.resolveGovernanceResourceId(ctx, requestFacade), e.getMessage());
                }
                handleGovernanceFailure(response, e, ctx);
                return;
            }

            filterChain.doFilter(request, response);
        } finally {
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
