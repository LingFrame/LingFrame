package com.lingframe.starter.web;

import io.swagger.v3.oas.models.OpenAPI;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link LingOpenApiCustomizerAdapter} 补充测试。
 * <p>
 * 该接口是函数式接口，抽象方法为 {@link #customise(OpenAPI)}，
 * 另有两个 default 方法用于支持路径过滤。本测试通过 lambda 与匿名实现
 * 验证 default 方法的委派行为。
 */
@DisplayName("LingOpenApiCustomizerAdapter 补充测试")
class LingOpenApiCustomizerAdapterSupplementTest {

    @Test
    @DisplayName("customise(openApi, paths) 默认实现应委派给 customise(openApi)")
    void shouldDelegateToSingleArgCustomise() {
        OpenAPI openApi = new OpenAPI();
        List<String> visited = new ArrayList<>();

        LingOpenApiCustomizerAdapter adapter = api -> visited.add("invoked");

        adapter.customise(openApi, Collections.singletonList("/ling/**"));

        assertEquals(1, visited.size(), "customise(OpenAPI) 应被调用一次");
    }

    @Test
    @DisplayName("customise 4 参数默认实现应委派到 2 参数版本")
    void shouldDelegateFourArgToTwoArg() {
        OpenAPI openApi = new OpenAPI();

        List<String> twoArgInvocations = new ArrayList<>();
        List<String> capturedPaths = new ArrayList<>();

        LingOpenApiCustomizerAdapter adapter = new LingOpenApiCustomizerAdapter() {
            @Override
            public void customise(OpenAPI api) {
                twoArgInvocations.add("single");
            }

            @Override
            public void customise(OpenAPI api, Collection<String> pathsToMatch) {
                twoArgInvocations.add("two-arg");
                if (pathsToMatch != null) {
                    capturedPaths.addAll(pathsToMatch);
                }
            }
        };

        Collection<String> matchPaths = Arrays.asList("/a/**", "/b/**");
        adapter.customise(openApi, matchPaths, Arrays.asList("com.pkg"), Arrays.asList("/x"), Arrays.asList("com.other"));

        // 应委派到 2 参数版本，并透传 pathsToMatch
        assertTrue(twoArgInvocations.contains("two-arg"));
        assertFalse(twoArgInvocations.contains("single"), "4 参数默认实现不应直接调用 single 参数版本");
        assertEquals(Arrays.asList("/a/**", "/b/**"), capturedPaths);
    }

    @Test
    @DisplayName("函数式接口应能通过 lambda 实例化并修改 OpenAPI")
    void shouldBeUsableAsLambda() {
        OpenAPI openApi = new OpenAPI();
        LingOpenApiCustomizerAdapter adapter = api -> api.addExtension("x-ling", "value");

        adapter.customise(openApi);

        assertEquals("value", openApi.getExtensions().get("x-ling"));
        // 2 参数版本最终也调用 single 参数版本，扩展应仍存在
        adapter.customise(openApi, Collections.singletonList("/x"));
        assertEquals("value", openApi.getExtensions().get("x-ling"));
    }

    @Test
    @DisplayName("customise 4 参数默认实现传入 null pathsToMatch 时应安全委派")
    void shouldHandleNullPathsInFourArgDefault() {
        OpenAPI openApi = new OpenAPI();
        boolean[] invoked = new boolean[1];

        LingOpenApiCustomizerAdapter adapter = new LingOpenApiCustomizerAdapter() {
            @Override
            public void customise(OpenAPI api) {
                invoked[0] = true;
            }
        };

        // 4 参数版本传 null pathsToMatch，应不抛异常并委派到 2 参数版本再到 single 版本
        adapter.customise(openApi, null, null, null, null);

        assertTrue(invoked[0]);
    }

    @Test
    @DisplayName("customise 2 参数版本应直接复用传入的 OpenAPI 实例")
    void shouldReuseSameOpenApiInstance() {
        OpenAPI openApi = new OpenAPI();
        LingOpenApiCustomizerAdapter adapter = api -> assertSame(openApi, api);

        adapter.customise(openApi, Collections.singleton("/p"));
    }
}
