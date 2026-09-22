package com.lingframe.api.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * GovernancePolicy copy() 反射覆盖断言测试。
 * <p>
 * 生产就绪守护：未来新增字段若忘改 copy()，此测试立即失败，
 * 防止治理 patch 丢字段导致生产配置漂移。
 */
@DisplayName("GovernancePolicy copy() 反射覆盖断言")
class GovernancePolicyCopyCoverageTest {

    @Test
    @DisplayName("copy() 必须覆盖 GovernancePolicy 所有非静态字段")
    void copyMustCoverAllFields() throws ReflectiveOperationException {
        GovernancePolicy original = buildFullyPopulatedPolicy();
        GovernancePolicy copy = original.copy();

        for (Field f : GovernancePolicy.class.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers())) continue;
            f.setAccessible(true);
            Object origVal = f.get(original);
            assertNotNull(origVal, "测试构造失败：字段 " + f.getName() + " 未填充");
            Object copyVal = f.get(copy);
            assertNotNull(copyVal, "copy() 遗漏字段：" + f.getName());
            assertFieldCopied(f.getName(), origVal, copyVal);
        }
    }

    @Test
    @DisplayName("每个内部类的 copy() 必须覆盖其所有非静态字段")
    void innerClassCopyMustCoverAllFields() throws ReflectiveOperationException {
        assertInnerClassCopied(GovernancePolicy.PermissionRule.class, buildPermissionRule());
        assertInnerClassCopied(GovernancePolicy.CapabilityRule.class, buildCapabilityRule());
        assertInnerClassCopied(GovernancePolicy.AuditRule.class, buildAuditRule());
        assertInnerClassCopied(GovernancePolicy.InvocationPolicy.class, buildInvocationPolicy());
    }

    private void assertInnerClassCopied(Class<?> clazz, Object original)
            throws ReflectiveOperationException {
        Object copy;
        try {
            copy = clazz.getMethod("copy").invoke(original);
        } catch (NoSuchMethodException e) {
            return; // 无 copy 方法的内部类跳过
        }
        for (Field f : clazz.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers())) continue;
            f.setAccessible(true);
            Object origVal = f.get(original);
            Object copyVal = f.get(copy);
            if (origVal == null) continue;
            assertNotNull(copyVal, clazz.getSimpleName() + ".copy() 遗漏字段：" + f.getName());
            assertFieldCopied(clazz.getSimpleName() + "." + f.getName(), origVal, copyVal);
        }
    }

    private void assertFieldCopied(String fieldName, Object origVal, Object copyVal) {
        if (origVal instanceof List) {
            List<?> origList = (List<?>) origVal;
            List<?> copyList = (List<?>) copyVal;
            assertNotSame(origVal, copyVal, "字段 " + fieldName + " 应深拷贝（List 引用不同）");
            assertEquals(origList.size(), copyList.size(),
                    "字段 " + fieldName + " 深拷贝后 size 应相等");
            for (int i = 0; i < origList.size(); i++) {
                assertNotSame(origList.get(i), copyList.get(i),
                        "字段 " + fieldName + "[" + i + "] 应深拷贝（元素引用不同）");
            }
        } else if (origVal instanceof GovernancePolicy.InvocationPolicy) {
            // InvocationPolicy 未重写 equals，验证引用不同（证明深拷贝）即可
            assertNotSame(origVal, copyVal, "字段 " + fieldName + " 应深拷贝（引用不同）");
        } else {
            // String / enum / 基本类型包装类等已重写 equals，验证值相等
            assertEquals(origVal, copyVal, "字段 " + fieldName + " 值应相等");
        }
    }

    // ==================== 测试数据构造（反射设置 private 字段） ====================

    private GovernancePolicy buildFullyPopulatedPolicy() throws ReflectiveOperationException {
        GovernancePolicy p = new GovernancePolicy();
        setField(p, "permissions", new ArrayList<>(Collections.singletonList(buildPermissionRule())));
        setField(p, "capabilities", new ArrayList<>(Collections.singletonList(buildCapabilityRule())));
        setField(p, "audits", new ArrayList<>(Collections.singletonList(buildAuditRule())));
        setField(p, "invocation", buildInvocationPolicy());
        return p;
    }

    private GovernancePolicy.PermissionRule buildPermissionRule() throws ReflectiveOperationException {
        GovernancePolicy.PermissionRule r = new GovernancePolicy.PermissionRule();
        setField(r, "methodPattern", "storage:sql");
        setField(r, "permissionId", "READ");
        return r;
    }

    private GovernancePolicy.CapabilityRule buildCapabilityRule() throws ReflectiveOperationException {
        GovernancePolicy.CapabilityRule r = new GovernancePolicy.CapabilityRule();
        setField(r, "capability", "file:write");
        setField(r, "accessType", "WRITE");
        return r;
    }

    private GovernancePolicy.AuditRule buildAuditRule() throws ReflectiveOperationException {
        GovernancePolicy.AuditRule r = new GovernancePolicy.AuditRule();
        setField(r, "methodPattern", "storage:*");
        setField(r, "action", "log");
        return r;
    }

    private GovernancePolicy.InvocationPolicy buildInvocationPolicy() throws ReflectiveOperationException {
        GovernancePolicy.InvocationPolicy p = new GovernancePolicy.InvocationPolicy();
        setField(p, "timeoutMs", 5000);
        setField(p, "rateLimitPerSecond", 100);
        return p;
    }

    private static void setField(Object target, String fieldName, Object value) throws ReflectiveOperationException {
        Field f = target.getClass().getDeclaredField(fieldName);
        f.setAccessible(true);
        f.set(target, value);
    }
}
