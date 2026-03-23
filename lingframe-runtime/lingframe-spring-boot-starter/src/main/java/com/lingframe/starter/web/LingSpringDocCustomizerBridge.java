package com.lingframe.starter.web;

import io.swagger.v3.oas.models.OpenAPI;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SpringDoc 分组桥接器。
 *
 * <p>负责把 SpringDoc v1/v2 的全局 / 分组自定义器接口统一桥接到
 * {@link LingOpenApiCustomizerAdapter}，避免灵核侧继续理解 SpringDoc 的内部流程差异。</p>
 */
public final class LingSpringDocCustomizerBridge {

    private static final String V1_OPENAPI_CUSTOMISER = "org.springdoc.core.customizers.OpenApiCustomiser";
    private static final String V1_GLOBAL_CUSTOMISER = "org.springdoc.core.customizers.GlobalOpenApiCustomizer";
    private static final String V2_OPENAPI_CUSTOMIZER = "org.springdoc.core.customizers.OpenApiCustomizer";
    private static final String V2_GLOBAL_CUSTOMIZER = "org.springdoc.core.customizers.GlobalOpenApiCustomizer";

    private LingSpringDocCustomizerBridge() {
    }

    public static Object createGlobalCustomizer(ClassLoader classLoader, LingOpenApiCustomizerAdapter adapter) {
        Class<?>[] interfaces = resolveInterfaces(classLoader, V1_GLOBAL_CUSTOMISER, V1_OPENAPI_CUSTOMISER,
                V2_GLOBAL_CUSTOMIZER, V2_OPENAPI_CUSTOMIZER);
        if (interfaces.length == 0) {
            return null;
        }
        return Proxy.newProxyInstance(classLoader, interfaces, new GlobalInvocationHandler(adapter));
    }

    public static Object createGroupedCustomizer(ClassLoader classLoader,
                                                 LingOpenApiCustomizerAdapter adapter,
                                                 Object groupedOpenApi) {
        if (groupedOpenApi == null) {
            return null;
        }

        Class<?> groupedType = groupedOpenApi.getClass();
        String openApiCustomizerInterface = resolveGroupedCustomizerInterface(groupedType.getName());
        if (openApiCustomizerInterface == null) {
            return null;
        }

        Class<?> customizerInterface = loadClass(classLoader, openApiCustomizerInterface);
        if (customizerInterface == null) {
            return null;
        }

        GroupedInvocationHandler handler = new GroupedInvocationHandler(adapter, groupedOpenApi);
        return Proxy.newProxyInstance(classLoader, new Class<?>[] {customizerInterface}, handler);
    }

    public static boolean attachToGroupedOpenApi(ClassLoader classLoader,
                                                 LingOpenApiCustomizerAdapter adapter,
                                                 Object groupedOpenApi) {
        if (groupedOpenApi == null) {
            return false;
        }

        Object customizer = createGroupedCustomizer(classLoader, adapter, groupedOpenApi);
        if (customizer == null) {
            return false;
        }

        Method addAllMethod = findMethod(groupedOpenApi.getClass(), "addAllOpenApiCustomizer", Collection.class);
        if (addAllMethod == null) {
            addAllMethod = findMethod(groupedOpenApi.getClass(), "addAllOpenApiCustomiser", Collection.class);
        }
        if (addAllMethod == null) {
            return false;
        }

        invoke(addAllMethod, groupedOpenApi, Collections.singleton(customizer));
        return true;
    }

    private static String resolveGroupedCustomizerInterface(String groupedTypeName) {
        if ("org.springdoc.core.GroupedOpenApi".equals(groupedTypeName)) {
            return V1_OPENAPI_CUSTOMISER;
        }
        if ("org.springdoc.core.models.GroupedOpenApi".equals(groupedTypeName)) {
            return V2_OPENAPI_CUSTOMIZER;
        }
        return null;
    }

    private static Class<?>[] resolveInterfaces(ClassLoader classLoader, String... interfaceNames) {
        Map<String, Class<?>> resolved = new LinkedHashMap<>();
        for (String interfaceName : interfaceNames) {
            Class<?> type = loadClass(classLoader, interfaceName);
            if (type != null) {
                resolved.put(type.getName(), type);
            }
        }
        List<Class<?>> interfaces = new ArrayList<>(resolved.values());
        return interfaces.toArray(new Class<?>[0]);
    }

    private static Class<?> loadClass(ClassLoader classLoader, String className) {
        try {
            return Class.forName(className, false, classLoader);
        } catch (ClassNotFoundException ex) {
            return null;
        }
    }

    private static Method findMethod(Class<?> type, String name, Class<?>... parameterTypes) {
        try {
            return type.getMethod(name, parameterTypes);
        } catch (NoSuchMethodException ex) {
            return null;
        }
    }

    private static Object invoke(Method method, Object target, Object... args) {
        try {
            return method.invoke(target, args);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Failed to invoke " + method.getName() + " on " + target.getClass().getName(), ex);
        }
    }

    private abstract static class BaseInvocationHandler implements InvocationHandler {
        protected final LingOpenApiCustomizerAdapter adapter;

        private BaseInvocationHandler(LingOpenApiCustomizerAdapter adapter) {
            this.adapter = adapter;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            String methodName = method.getName();
            if ("customise".equals(methodName) && args != null && args.length == 1 && args[0] instanceof OpenAPI) {
                customise((OpenAPI) args[0]);
                return null;
            }
            if ("equals".equals(methodName)) {
                return proxy == args[0];
            }
            if ("hashCode".equals(methodName)) {
                return System.identityHashCode(proxy);
            }
            if ("toString".equals(methodName)) {
                return getClass().getSimpleName();
            }
            return null;
        }

        protected abstract void customise(OpenAPI openApi);
    }

    private static final class GlobalInvocationHandler extends BaseInvocationHandler {
        private GlobalInvocationHandler(LingOpenApiCustomizerAdapter adapter) {
            super(adapter);
        }

        @Override
        protected void customise(OpenAPI openApi) {
            adapter.customise(openApi);
        }
    }

    private static final class GroupedInvocationHandler extends BaseInvocationHandler {
        private final Object groupedOpenApi;

        private GroupedInvocationHandler(LingOpenApiCustomizerAdapter adapter, Object groupedOpenApi) {
            super(adapter);
            this.groupedOpenApi = groupedOpenApi;
        }

        @Override
        protected void customise(OpenAPI openApi) {
            adapter.customise(
                    openApi,
                    readStringCollection("getPathsToMatch"),
                    readStringCollection("getPackagesToScan"),
                    readStringCollection("getPathsToExclude"),
                    readStringCollection("getPackagesToExclude"));
        }

        @SuppressWarnings("unchecked")
        private Collection<String> readStringCollection(String methodName) {
            Method method = findMethod(groupedOpenApi.getClass(), methodName);
            if (method == null) {
                return null;
            }
            Object value = LingSpringDocCustomizerBridge.invoke(method, groupedOpenApi);
            return value instanceof Collection ? (Collection<String>) value : null;
        }
    }
}
