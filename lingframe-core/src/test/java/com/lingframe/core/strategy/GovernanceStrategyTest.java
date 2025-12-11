package com.lingframe.core.strategy;

import com.lingframe.api.security.AccessType;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

class GovernanceStrategyTest {

    @Test
    void testInferAccessType() {
        // 测试读操作
        assertEquals(AccessType.READ, GovernanceStrategy.inferAccessType("getSomething"));
        assertEquals(AccessType.READ, GovernanceStrategy.inferAccessType("findSomething"));
        assertEquals(AccessType.READ, GovernanceStrategy.inferAccessType("querySomething"));
        assertEquals(AccessType.READ, GovernanceStrategy.inferAccessType("listSomething"));
        assertEquals(AccessType.READ, GovernanceStrategy.inferAccessType("selectSomething"));
        assertEquals(AccessType.READ, GovernanceStrategy.inferAccessType("countSomething"));
        assertEquals(AccessType.READ, GovernanceStrategy.inferAccessType("checkSomething"));
        assertEquals(AccessType.READ, GovernanceStrategy.inferAccessType("isSomething"));
        assertEquals(AccessType.READ, GovernanceStrategy.inferAccessType("hasSomething"));

        // 测试写操作
        assertEquals(AccessType.WRITE, GovernanceStrategy.inferAccessType("createSomething"));
        assertEquals(AccessType.WRITE, GovernanceStrategy.inferAccessType("saveSomething"));
        assertEquals(AccessType.WRITE, GovernanceStrategy.inferAccessType("insertSomething"));
        assertEquals(AccessType.WRITE, GovernanceStrategy.inferAccessType("updateSomething"));
        assertEquals(AccessType.WRITE, GovernanceStrategy.inferAccessType("modifySomething"));
        assertEquals(AccessType.WRITE, GovernanceStrategy.inferAccessType("deleteSomething"));
        assertEquals(AccessType.WRITE, GovernanceStrategy.inferAccessType("removeSomething"));
        assertEquals(AccessType.WRITE, GovernanceStrategy.inferAccessType("addSomething"));
        assertEquals(AccessType.WRITE, GovernanceStrategy.inferAccessType("setSomething"));

        // 测试执行操作（默认情况）
        assertEquals(AccessType.EXECUTE, GovernanceStrategy.inferAccessType("executeSomething"));
        assertEquals(AccessType.EXECUTE, GovernanceStrategy.inferAccessType("runSomething"));
    }

    @Test
    void testInferPermission() {
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