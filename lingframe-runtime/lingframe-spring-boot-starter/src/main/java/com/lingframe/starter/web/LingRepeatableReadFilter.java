package com.lingframe.starter.web;

import lombok.extern.slf4j.Slf4j;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.StreamUtils;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * 灵珑可重复读过滤器 (版本无关实现)。
 * <p>
 * 该类不直接继承 OncePerRequestFilter 或实现 Filter 接口，
 * 而是通过动态代理在运行时适配 javax.servlet 或 jakarta.servlet。
 */
@Slf4j
public class LingRepeatableReadFilter {

    private static final String ATTRIBUTE_NAME = LingRepeatableReadFilter.class.getName() + ".FILTERED";

    /**
     * 创建一个符合当前环境的 Filter 代理对象。
     */
    public static Object createProxy() {
        ClassLoader cl = LingRepeatableReadFilter.class.getClassLoader();
        Class<?> filterClass = null;
        try {
            filterClass = Class.forName("jakarta.servlet.Filter", false, cl);
        } catch (ClassNotFoundException e) {
            try {
                filterClass = Class.forName("javax.servlet.Filter", false, cl);
            } catch (ClassNotFoundException ex) {
                log.warn("No Servlet Filter class found on classpath");
                return null;
            }
        }

        return Proxy.newProxyInstance(cl, new Class[]{filterClass}, new FilterInvocationHandler());
    }

    private static class FilterInvocationHandler implements InvocationHandler {
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (method.getDeclaringClass() == Object.class) {
                String name = method.getName();
                if ("toString".equals(name)) return "LingRepeatableReadFilterProxy";
                if ("hashCode".equals(name)) return System.identityHashCode(proxy);
                if ("equals".equals(name)) return proxy == args[0];
                return method.invoke(this, args);
            }
            String name = method.getName();
            if ("doFilter".equals(name) && args.length == 3) {
                return doFilter(args[0], args[1], args[2]);
            } else if ("init".equals(name) || "destroy".equals(name)) {
                return null;
            }
            return null;
        }

        private Object doFilter(Object request, Object response, Object chain) throws Exception {
            // 获取 getAttribute / setAttribute 方法
            Method getAttrMethod = ReflectionUtils.findMethod(request.getClass(), "getAttribute", String.class);
            Method setAttrMethod = ReflectionUtils.findMethod(request.getClass(), "setAttribute", String.class, Object.class);
            
            String pkgName = request.getClass().getInterfaces()[0].getName().contains("jakarta") ? "jakarta.servlet" : "javax.servlet";
            Method doFilterMethod = ReflectionUtils.findMethod(chain.getClass(), "doFilter", 
                    Class.forName(pkgName + ".ServletRequest"),
                    Class.forName(pkgName + ".ServletResponse"));

            if (getAttrMethod != null && ReflectionUtils.invokeMethod(getAttrMethod, request, ATTRIBUTE_NAME) != null) {
                if (doFilterMethod != null) ReflectionUtils.invokeMethod(doFilterMethod, chain, request, response);
                return null;
            }

            if (setAttrMethod != null) ReflectionUtils.invokeMethod(setAttrMethod, request, ATTRIBUTE_NAME, Boolean.TRUE);

            // 包装请求
            Object wrappedRequest = createRequestProxy(request);
            if (doFilterMethod != null) {
                ReflectionUtils.invokeMethod(doFilterMethod, chain, wrappedRequest, response);
            }
            return null;
        }
    }

    private static Object createRequestProxy(Object request) {
        Class<?>[] interfaces = request.getClass().getInterfaces();
        // 寻找 HttpServletRequest 接口
        Class<?> requestInterface = null;
        for (Class<?> iface : interfaces) {
            if (iface.getName().endsWith(".HttpServletRequest")) {
                requestInterface = iface;
                break;
            }
        }
        if (requestInterface == null) return request;

        return Proxy.newProxyInstance(request.getClass().getClassLoader(), 
                new Class[]{requestInterface}, new RequestInvocationHandler(request));
    }

    private static class RequestInvocationHandler implements InvocationHandler {
        private final Object originalRequest;
        private byte[] body;
        private boolean bodyLoaded = false;

        public RequestInvocationHandler(Object originalRequest) {
            this.originalRequest = originalRequest;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (method.getDeclaringClass() == Object.class) {
                return method.invoke(originalRequest, args);
            }
            String name = method.getName();
            if ("getInputStream".equals(name)) {
                return getInputStream();
            } else if ("getReader".equals(name)) {
                return new BufferedReader(new InputStreamReader((java.io.InputStream) getInputStream(), getCharacterEncoding()));
            }
            // 默认调用原始对象
            return method.invoke(originalRequest, args);
        }

        private String getCharacterEncoding() {
            Method m = ReflectionUtils.findMethod(originalRequest.getClass(), "getCharacterEncoding");
            return (String) ReflectionUtils.invokeMethod(m, originalRequest);
        }

        private Object getInputStream() throws IOException {
            if (!bodyLoaded) {
                Method getIn = ReflectionUtils.findMethod(originalRequest.getClass(), "getInputStream");
                Object originalIn = ReflectionUtils.invokeMethod(getIn, originalRequest);
                if (originalIn != null) {
                    java.io.InputStream is = (java.io.InputStream) originalIn;
                    body = StreamUtils.copyToByteArray(is);
                } else {
                    body = new byte[0];
                }
                bodyLoaded = true;
            }
            String basePackage = originalRequest.getClass().getInterfaces()[0].getName().contains("jakarta") ? "jakarta.servlet" : "javax.servlet";
            return createInputStream(originalRequest.getClass().getClassLoader(), body, basePackage);
        }
    }

    private static Object createInputStream(ClassLoader cl, byte[] body, String basePackage) {
        String adapterClassName = basePackage.contains("jakarta") 
                ? "com.lingframe.starter.web.adapter.JakartaRepeatableReadInputStream"
                : "com.lingframe.starter.web.adapter.JavaxRepeatableReadInputStream";
        try {
            Class<?> adapterClass = Class.forName(adapterClassName, true, cl);
            return adapterClass.getConstructor(byte[].class).newInstance((Object) body);
        } catch (Exception e) {
            log.error("Failed to create RepeatableReadInputStream adapter ({}): {}", adapterClassName, e.getMessage());
            return new ByteArrayInputStream(body); // 最后的回退方案
        }
    }
}
