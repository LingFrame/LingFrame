package com.lingframe.starter.web;

import lombok.extern.slf4j.Slf4j;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.StreamUtils;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
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

    // 启动期一次性探测 servlet API 版本,运行时只读常量,避免热路径反射和数组越界
    private static final String SERVLET_PACKAGE;
    private static final Class<?> SERVLET_REQUEST_CLASS;

    static {
        Class<?> reqClass;
        String pkg;
        try {
            reqClass = Class.forName("jakarta.servlet.ServletRequest");
            pkg = "jakarta.servlet";
        } catch (ClassNotFoundException e) {
            try {
                reqClass = Class.forName("javax.servlet.ServletRequest");
                pkg = "javax.servlet";
            } catch (ClassNotFoundException ex) {
                log.warn("No Servlet API found on classpath, LingRepeatableReadFilter disabled");
                reqClass = null;
                pkg = "javax.servlet"; // 兜底默认值（reqClass=null 时 createProxy 直接返回 null）
            }
        }
        SERVLET_REQUEST_CLASS = reqClass;
        SERVLET_PACKAGE = pkg;
    }

    /**
     * 创建一个符合当前环境的 Filter 代理对象。
     */
    public static Object createProxy() {
        if (SERVLET_REQUEST_CLASS == null) {
            return null;
        }
        ClassLoader cl = LingRepeatableReadFilter.class.getClassLoader();
        Class<?> filterClass;
        try {
            filterClass = Class.forName(SERVLET_PACKAGE + ".Filter", false, cl);
        } catch (ClassNotFoundException e) {
            log.warn("No Servlet Filter class found on classpath");
            return null;
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

            // 直接使用启动期探测的 SERVLET_PACKAGE 常量，避免热路径反射和 getInterfaces()[0] 数组越界
            Method doFilterMethod = ReflectionUtils.findMethod(chain.getClass(), "doFilter",
                    Class.forName(SERVLET_PACKAGE + ".ServletRequest"),
                    Class.forName(SERVLET_PACKAGE + ".ServletResponse"));

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
                return new BufferedReader(new InputStreamReader((InputStream) getInputStream(), getCharacterEncoding()));
            }
            // 默认调用原始对象
            return method.invoke(originalRequest, args);
        }

        private String getCharacterEncoding() {
            Method m = ReflectionUtils.findMethod(originalRequest.getClass(), "getCharacterEncoding");
            String encoding = (String) ReflectionUtils.invokeMethod(m, originalRequest);
            // Servlet 规范：getCharacterEncoding() 返回 null 表示请求未指定字符编码，
            // 此时容器应使用默认编码 ISO-8859-1（RFC 7230）。
            // 回退避免 new InputStreamReader(InputStream, null) 抛 NullPointerException
            return encoding != null ? encoding : "ISO-8859-1";
        }

        private Object getInputStream() throws IOException {
            if (!bodyLoaded) {
                Method getIn = ReflectionUtils.findMethod(originalRequest.getClass(), "getInputStream");
                Object originalIn = ReflectionUtils.invokeMethod(getIn, originalRequest);
                if (originalIn != null) {
                    InputStream is = (InputStream) originalIn;
                    body = StreamUtils.copyToByteArray(is);
                } else {
                    body = new byte[0];
                }
                bodyLoaded = true;
            }
            // 直接复用启动期探测的 SERVLET_PACKAGE 常量，避免热路径反射和数组越界
            return createInputStream(originalRequest.getClass().getClassLoader(), body);
        }
    }

    private static Object createInputStream(ClassLoader cl, byte[] body) {
        String adapterClassName = SERVLET_PACKAGE.equals("jakarta.servlet")
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
