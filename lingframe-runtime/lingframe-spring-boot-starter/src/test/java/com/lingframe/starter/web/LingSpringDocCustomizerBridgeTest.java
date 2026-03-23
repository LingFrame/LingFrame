package com.lingframe.starter.web;

import io.swagger.v3.oas.models.OpenAPI;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("LingSpringDocCustomizerBridge 测试")
class LingSpringDocCustomizerBridgeTest {

    @Test
    @DisplayName("应将分组规则桥接给 LingOpenApiCustomizerAdapter")
    void shouldBridgeGroupedRulesIntoAdapter() throws Exception {
        Object groupedOpenApi = createGroupedOpenApi();
        RecordingAdapter adapter = new RecordingAdapter();

        boolean attached = LingSpringDocCustomizerBridge.attachToGroupedOpenApi(
                groupedOpenApi.getClass().getClassLoader(), adapter, groupedOpenApi);

        assertTrue(attached);

        Object customizer = readFirstCustomizer(groupedOpenApi);
        assertNotNull(customizer);

        Method customiseMethod = customizer.getClass().getMethod("customise", OpenAPI.class);
        customiseMethod.invoke(customizer, new OpenAPI());

        assertEquals(listOf("/**-ling/**"), adapter.pathsToMatch);
        assertEquals(listOf("com.example.api"), adapter.packagesToScan);
        assertEquals(listOf("/internal/**"), adapter.pathsToExclude);
        assertEquals(listOf("com.example.internal"), adapter.packagesToExclude);
        assertFalse(adapter.globalCalled);
    }

    private Object createGroupedOpenApi() throws Exception {
        Class<?> groupedType = resolveGroupedType();
        Method builderMethod = groupedType.getMethod("builder");
        Object builder = builderMethod.invoke(null);

        invokeBuilder(builder, "group", "lings");
        invokeBuilder(builder, "pathsToMatch", new String[] {"/**-ling/**"});
        invokeBuilder(builder, "packagesToScan", new String[] {"com.example.api"});
        invokeBuilder(builder, "pathsToExclude", new String[] {"/internal/**"});
        invokeBuilder(builder, "packagesToExclude", new String[] {"com.example.internal"});

        Method buildMethod = builder.getClass().getMethod("build");
        return buildMethod.invoke(builder);
    }

    private void invokeBuilder(Object builder, String methodName, String[] values) throws Exception {
        Method method = builder.getClass().getMethod(methodName, String[].class);
        method.invoke(builder, new Object[] {values});
    }

    private void invokeBuilder(Object builder, String methodName, String value) throws Exception {
        Method method = builder.getClass().getMethod(methodName, String.class);
        method.invoke(builder, value);
    }

    private Object readFirstCustomizer(Object groupedOpenApi) throws Exception {
        Method getter = findMethod(groupedOpenApi.getClass(), "getOpenApiCustomizers");
        if (getter == null) {
            getter = findMethod(groupedOpenApi.getClass(), "getOpenApiCustomisers");
        }
        assertNotNull(getter);

        Object value = getter.invoke(groupedOpenApi);
        assertTrue(value instanceof Collection);
        Collection<?> customizers = (Collection<?>) value;
        assertEquals(1, customizers.size());
        return customizers.iterator().next();
    }

    private Method findMethod(Class<?> type, String methodName, Class<?>... parameterTypes) {
        try {
            return type.getMethod(methodName, parameterTypes);
        } catch (NoSuchMethodException ex) {
            return null;
        }
    }

    private Class<?> resolveGroupedType() throws ClassNotFoundException {
        try {
            return Class.forName("org.springdoc.core.models.GroupedOpenApi");
        } catch (ClassNotFoundException ex) {
            return Class.forName("org.springdoc.core.GroupedOpenApi");
        }
    }

    private List<String> listOf(String value) {
        List<String> list = new ArrayList<>();
        list.add(value);
        return list;
    }

    private static final class RecordingAdapter implements LingOpenApiCustomizerAdapter {
        private boolean globalCalled;
        private Collection<String> pathsToMatch;
        private Collection<String> packagesToScan;
        private Collection<String> pathsToExclude;
        private Collection<String> packagesToExclude;

        @Override
        public void customise(OpenAPI openApi) {
            globalCalled = true;
        }

        @Override
        public void customise(OpenAPI openApi,
                              Collection<String> pathsToMatch,
                              Collection<String> packagesToScan,
                              Collection<String> pathsToExclude,
                              Collection<String> packagesToExclude) {
            this.pathsToMatch = pathsToMatch;
            this.packagesToScan = packagesToScan;
            this.pathsToExclude = pathsToExclude;
            this.packagesToExclude = packagesToExclude;
        }
    }
}
