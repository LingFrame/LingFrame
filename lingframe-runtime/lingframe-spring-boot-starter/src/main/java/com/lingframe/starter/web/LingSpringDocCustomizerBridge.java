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
 * {@link LingOpenApiCustomizerAdapter}。</p>
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

    private static final class GlobalInvocationHandler implements InvocationHandler {
        private final LingOpenApiCustomizerAdapter adapter;

        private GlobalInvocationHandler(LingOpenApiCustomizerAdapter adapter) {
            this.adapter = adapter;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            if (method.getDeclaringClass() == Object.class) {
                return handleObjectMethod(proxy, method, args);
            }
            if ("customise".equals(method.getName()) || "customize".equals(method.getName())) {
                if (args != null && args.length >= 1 && args[0] instanceof OpenAPI) {
                    adapter.customise((OpenAPI) args[0]);
                }
            }
            return null;
        }
    }

    private static final class GroupedInvocationHandler implements InvocationHandler {
        private final LingOpenApiCustomizerAdapter adapter;
        private final Object groupedOpenApi;

        private GroupedInvocationHandler(LingOpenApiCustomizerAdapter adapter, Object groupedOpenApi) {
            this.adapter = adapter;
            this.groupedOpenApi = groupedOpenApi;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            if (method.getDeclaringClass() == Object.class) {
                return handleObjectMethod(proxy, method, args);
            }
            if (("customise".equals(method.getName()) || "customize".equals(method.getName()))
                    && args != null && args.length >= 1 && args[0] instanceof OpenAPI) {
                Collection<String> pathsToMatch = readStringCollection(groupedOpenApi, "getPathsToMatch");
                Collection<String> packagesToScan = readStringCollection(groupedOpenApi, "getPackagesToScan");
                Collection<String> pathsToExclude = readStringCollection(groupedOpenApi, "getPathsToExclude");
                Collection<String> packagesToExclude = readStringCollection(groupedOpenApi, "getPackagesToExclude");
                adapter.customise((OpenAPI) args[0], pathsToMatch, packagesToScan, pathsToExclude, packagesToExclude);
            }
            return null;
        }
    }

    private static Object handleObjectMethod(Object proxy, Method method, Object[] args) {
        String name = method.getName();
        if ("toString".equals(name)) {
            return "LingSpringDocCustomizerBridgeProxy";
        }
        if ("hashCode".equals(name)) {
            return System.identityHashCode(proxy);
        }
        if ("equals".equals(name)) {
            return proxy == args[0];
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Collection<String> readStringCollection(Object target, String methodName) {
        Method method = findMethod(target.getClass(), methodName);
        if (method == null) {
            return Collections.emptyList();
        }
        Object value = invoke(method, target);
        if (value instanceof Collection) {
            return (Collection<String>) value;
        }
        return Collections.emptyList();
    }
}
