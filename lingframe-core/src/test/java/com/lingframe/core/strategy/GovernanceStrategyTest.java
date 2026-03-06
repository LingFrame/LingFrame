package com.lingframe.core.strategy;

import com.lingframe.api.security.AccessType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("GovernanceStrategy 灵元测试")
class GovernanceStrategyTest {

    @Nested
    @DisplayName("访问类型推断")
    class AccessTypeInferenceTests {

        @Test
        @DisplayName("应正确推断读操作")
        void shouldInferReadAccess() {
            assertEquals(AccessType.READ, GovernanceStrategy.inferAccessType("getSomething"));
            assertEquals(AccessType.READ, GovernanceStrategy.inferAccessType("findSomething"));
            assertEquals(AccessType.READ, GovernanceStrategy.inferAccessType("querySomething"));
            assertEquals(AccessType.READ, GovernanceStrategy.inferAccessType("listSomething"));
            assertEquals(AccessType.READ, GovernanceStrategy.inferAccessType("selectSomething"));
            assertEquals(AccessType.READ, GovernanceStrategy.inferAccessType("countSomething"));
            assertEquals(AccessType.READ, GovernanceStrategy.inferAccessType("checkSomething"));
            assertEquals(AccessType.READ, GovernanceStrategy.inferAccessType("isSomething"));
            assertEquals(AccessType.READ, GovernanceStrategy.inferAccessType("hasSomething"));
        }

        @Test
        @DisplayName("应正确推断写操作")
        void shouldInferWriteAccess() {
            assertEquals(AccessType.WRITE, GovernanceStrategy.inferAccessType("createSomething"));
            assertEquals(AccessType.WRITE, GovernanceStrategy.inferAccessType("saveSomething"));
            assertEquals(AccessType.WRITE, GovernanceStrategy.inferAccessType("insertSomething"));
            assertEquals(AccessType.WRITE, GovernanceStrategy.inferAccessType("updateSomething"));
            assertEquals(AccessType.WRITE, GovernanceStrategy.inferAccessType("modifySomething"));
            assertEquals(AccessType.WRITE, GovernanceStrategy.inferAccessType("deleteSomething"));
            assertEquals(AccessType.WRITE, GovernanceStrategy.inferAccessType("removeSomething"));
            assertEquals(AccessType.WRITE, GovernanceStrategy.inferAccessType("addSomething"));
            assertEquals(AccessType.WRITE, GovernanceStrategy.inferAccessType("setSomething"));
        }

        @Test
        @DisplayName("默认应推断为执行操作")
        void shouldInferExecuteAccess() {
            assertEquals(AccessType.EXECUTE, GovernanceStrategy.inferAccessType("executeSomething"));
            assertEquals(AccessType.EXECUTE, GovernanceStrategy.inferAccessType("runSomething"));
            assertEquals(AccessType.EXECUTE, GovernanceStrategy.inferAccessType("unknownAction"));
        }
    }

    @Nested
    @DisplayName("权限推断")
    class PermissionInferenceTests {

        @Test
        @DisplayName("应基于类名推断权限")
        void shouldInferPermissionFromClassName() {
            // 直接测试方法名
            try {
                Method method = Object.class.getMethod("toString");
                String permission = GovernanceStrategy.inferPermission(method);
                assertTrue(permission.contains("Object"));
            } catch (NoSuchMethodException e) {
                fail("Failed to get method");
            }

            // 测试另一个方法
            try {
                Method method = String.class.getMethod("getBytes");
                String permission = GovernanceStrategy.inferPermission(method);
                assertTrue(permission.contains("String"));
            } catch (NoSuchMethodException e) {
                fail("Failed to get method");
            }
        }
    }
}