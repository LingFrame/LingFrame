package com.lingframe.core.routing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 局部作用域的身份与输入边界测试。 */
@DisplayName("限定灵元路由作用域")
class LingRoutingScopeTest {
    @Test
    @DisplayName("相同灵元和契约构成同一作用域")
    void valueIdentity() {
        LingRoutingScope scope = new LingRoutingScope("a", "execute");
        assertEquals(scope, scope);
        assertEquals(scope, new LingRoutingScope("a", "execute"));
        assertEquals(scope.hashCode(), new LingRoutingScope("a", "execute").hashCode());
        assertEquals("a", scope.getLingId());
        assertEquals("execute", scope.getContractId());
        assertEquals("a:execute", scope.toString());
        assertNotEquals(scope, new LingRoutingScope("b", "execute"));
        assertNotEquals(scope, new LingRoutingScope("a", "query"));
        assertNotEquals(scope, null);
        assertNotEquals(scope, "a:execute");
    }

    @Test
    @DisplayName("拒绝空标识及无法唯一解析的服务分隔符")
    void rejectsMalformedKeys() {
        for (String invalid : new String[] {null, "", " ", "a:b"}) {
            assertThrows(IllegalArgumentException.class, () -> new LingRoutingScope(invalid, "execute"));
            assertThrows(IllegalArgumentException.class, () -> new LingRoutingScope("a", invalid));
        }
    }
}
