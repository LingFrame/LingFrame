package com.lingframe.infra.storage.proxy;

import com.lingframe.api.security.AccessType;
import com.lingframe.api.security.PermissionService;
import com.lingframe.infra.storage.proxy.SqlPermissionSupport.ResolvedCapability;
import com.lingframe.infra.storage.proxy.SqlPermissionSupport.SqlPermissionPlan;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link SqlPermissionSupport} 关键语义测试。
 * 覆盖 P2-12 读写表区分、P2-13 表名归一化、P0-4 fail-closed。
 */
@DisplayName("SqlPermissionSupport 测试")
class SqlPermissionSupportTest {

    @Nested
    @DisplayName("读写表区分（P2-12）")
    class ReadWriteTableTests {

        @Test
        @DisplayName("SELECT 的表应作为读表，capability 带 :read 后缀")
        void shouldMarkSelectTablesAsRead() {
            SqlPermissionPlan plan = SqlPermissionSupport.analyze("select * from users");

            assertEquals(AccessType.READ, plan.getAccessType());
            // SELECT 表为读表，capability 带 :read 后缀
            assertTrue(plan.getCapabilities().contains("storage:sql:table:users:read"),
                    "expected read capability for SELECT table: " + plan.getCapabilities());
            // 不应包含不带后缀的写表 capability
            assertFalse(plan.getCapabilities().contains("storage:sql:table:users"),
                    "should not mark SELECT table as write: " + plan.getCapabilities());
        }

        @Test
        @DisplayName("INSERT INTO 单表应作为写表，capability 不带后缀")
        void shouldMarkInsertTableAsWrite() {
            SqlPermissionPlan plan = SqlPermissionSupport.analyze("insert into users (id) values (1)");

            assertEquals(AccessType.WRITE, plan.getAccessType());
            assertTrue(plan.getCapabilities().contains("storage:sql:table:users"),
                    "expected write capability for INSERT table: " + plan.getCapabilities());
        }

        @Test
        @DisplayName("INSERT INTO A SELECT FROM B：A 是写表，B 是读表")
        void shouldSeparateWriteAndReadTablesForInsertSelect() {
            SqlPermissionPlan plan = SqlPermissionSupport.analyze(
                    "insert into target (id) select id from source");

            assertEquals(AccessType.WRITE, plan.getAccessType());
            List<String> caps = plan.getCapabilities();
            // A 是写表
            assertTrue(caps.contains("storage:sql:table:target"),
                    "target should be write table: " + caps);
            // B 是读表（带 :read 后缀）
            assertTrue(caps.contains("storage:sql:table:source:read"),
                    "source should be read table: " + caps);
            // 不应对 source 要求 WRITE
            assertFalse(caps.contains("storage:sql:table:source"),
                    "source should not be marked as write: " + caps);
            // 不应对 target 要求 READ
            assertFalse(caps.contains("storage:sql:table:target:read"),
                    "target should not be marked as read: " + caps);
        }

        @Test
        @DisplayName("UPDATE 单表应作为写表")
        void shouldMarkUpdateTableAsWrite() {
            SqlPermissionPlan plan = SqlPermissionSupport.analyze("update users set name='a' where id=1");

            assertEquals(AccessType.WRITE, plan.getAccessType());
            assertTrue(plan.getCapabilities().contains("storage:sql:table:users"),
                    "expected write capability for UPDATE table: " + plan.getCapabilities());
        }

        @Test
        @DisplayName("DELETE 单表应作为写表")
        void shouldMarkDeleteTableAsWrite() {
            SqlPermissionPlan plan = SqlPermissionSupport.analyze("delete from users where id=1");

            assertEquals(AccessType.WRITE, plan.getAccessType());
            assertTrue(plan.getCapabilities().contains("storage:sql:table:users"),
                    "expected write capability for DELETE table: " + plan.getCapabilities());
        }

        @Test
        @DisplayName("多表 UPDATE（UPDATE t1 JOIN t2）的主表与 JOIN 从表均应为写表")
        void shouldMarkMultiTableUpdateAsWrite() {
            SqlPermissionPlan plan = SqlPermissionSupport.analyze(
                    "update users u join orders o on u.id = o.user_id set o.status = 1");

            assertEquals(AccessType.WRITE, plan.getAccessType());
            List<String> caps = plan.getCapabilities();
            // A11 修复：UPDATE 多表的主表与 JOIN 从表均为写表
            assertTrue(caps.contains("storage:sql:table:users"),
                    "users should be write table in multi-table UPDATE: " + caps);
            assertTrue(caps.contains("storage:sql:table:orders"),
                    "orders should be write table in multi-table UPDATE: " + caps);
        }

        @Test
        @DisplayName("多表 DELETE（DELETE t1 FROM t1 JOIN t2）目标表为写表，JOIN 表为读表")
        void shouldMarkMultiTableDeleteTargetAsWrite() {
            SqlPermissionPlan plan = SqlPermissionSupport.analyze(
                    "delete u from users u join orders o on u.id = o.user_id where o.status = 0");

            // JSqlParser 4.6 若不支持该 MySQL 多表 DELETE 语法，parseable=false，跳过断言
            assumeTrue(plan.isParseable(), "JSqlParser 4.6 不支持该多表 DELETE 语法");
            assertEquals(AccessType.WRITE, plan.getAccessType());
            List<String> caps = plan.getCapabilities();
            // DELETE 目标表（users）为写表
            assertTrue(caps.contains("storage:sql:table:users"),
                    "users (delete target) should be write table: " + caps);
            // JOIN 表（orders）用于行定位，为读表
            assertTrue(caps.contains("storage:sql:table:orders:read"),
                    "orders (join for locating) should be read table: " + caps);
            assertFalse(caps.contains("storage:sql:table:orders"),
                    "orders should not be write table in multi-table DELETE: " + caps);
            // 别名 u 不应生成幽灵 capability（DELETE 目标引用别名需解析到真实表名）
            assertFalse(caps.contains("storage:sql:table:u"),
                    "alias 'u' should not generate spurious capability: " + caps);
        }

        @Test
        @DisplayName("多目标 DELETE（DELETE a, b FROM t1 a JOIN t2 b）别名目标均解析为真实表名写表")
        void shouldResolveAliasesForMultiTargetDelete() {
            SqlPermissionPlan plan = SqlPermissionSupport.analyze(
                    "delete a, b from users a join orders b on a.id = b.user_id");

            // JSqlParser 4.6 若不支持该 MySQL 多目标 DELETE 语法，parseable=false，跳过断言
            assumeTrue(plan.isParseable(), "JSqlParser 4.6 不支持该多目标 DELETE 语法");
            List<String> caps = plan.getCapabilities();
            // 别名 a/b 解析为真实表名 users/orders，均为写表
            assertTrue(caps.contains("storage:sql:table:users"),
                    "users (delete target via alias a) should be write: " + caps);
            assertTrue(caps.contains("storage:sql:table:orders"),
                    "orders (delete target via alias b) should be write: " + caps);
            // 别名本身不应生成幽灵 capability
            assertFalse(caps.contains("storage:sql:table:a"),
                    "alias 'a' should not generate spurious capability: " + caps);
            assertFalse(caps.contains("storage:sql:table:b"),
                    "alias 'b' should not generate spurious capability: " + caps);
        }

        @Test
        @DisplayName("DROP TABLE 应作为写表（DDL）")
        void shouldMarkDropTableAsWrite() {
            SqlPermissionPlan plan = SqlPermissionSupport.analyze("drop table old_data");

            assertEquals(AccessType.EXECUTE, plan.getAccessType());
            assertTrue(plan.getCapabilities().contains("storage:sql:table:old_data"),
                    "expected write capability for DROP table: " + plan.getCapabilities());
        }

        @Test
        @DisplayName("TRUNCATE TABLE 应作为写表（DDL）")
        void shouldMarkTruncateTableAsWrite() {
            SqlPermissionPlan plan = SqlPermissionSupport.analyze("truncate table temp_log");

            assertEquals(AccessType.EXECUTE, plan.getAccessType());
            assertTrue(plan.getCapabilities().contains("storage:sql:table:temp_log"),
                    "expected write capability for TRUNCATE table: " + plan.getCapabilities());
        }

        @Test
        @DisplayName("JOIN SELECT 所有表应为读表")
        void shouldMarkAllJoinTablesAsRead() {
            SqlPermissionPlan plan = SqlPermissionSupport.analyze(
                    "select u.id, o.id from users u join orders o on u.id = o.user_id");

            assertEquals(AccessType.READ, plan.getAccessType());
            assertTrue(plan.getCapabilities().contains("storage:sql:table:users:read"),
                    "users should be read: " + plan.getCapabilities());
            assertTrue(plan.getCapabilities().contains("storage:sql:table:orders:read"),
                    "orders should be read: " + plan.getCapabilities());
        }
    }

    @Nested
    @DisplayName("表名归一化（P2-13）")
    class TableNameNormalizationTests {

        @Test
        @DisplayName("表名大小写不敏感：TABLE_A 与 table_a 等价")
        void shouldNormalizeTableNameCase() {
            SqlPermissionPlan plan = SqlPermissionSupport.analyze("select * from TABLE_A");
            assertTrue(plan.getCapabilities().contains("storage:sql:table:table_a:read"),
                    "expected normalized lowercase: " + plan.getCapabilities());
        }

        @Test
        @DisplayName("schema.table 应同时生成完整形式与 short form")
        void shouldGenerateShortFormForSchemaQualifiedTable() {
            SqlPermissionPlan plan = SqlPermissionSupport.analyze("select * from public.users");
            assertTrue(plan.getCapabilities().contains("storage:sql:table:public.users:read"),
                    "expected full form: " + plan.getCapabilities());
            assertTrue(plan.getCapabilities().contains("storage:sql:table:users:read"),
                    "expected short form: " + plan.getCapabilities());
        }
    }

    @Nested
    @DisplayName("权限解析（P2-12 + P0-4）")
    class ResolveCapabilityTests {

        @Test
        @DisplayName("写表要求 WRITE 权限，仅有 READ 应拒绝")
        void shouldRejectWriteTableWhenOnlyReadGranted() {
            PermissionService ps = mock(PermissionService.class);
            when(ps.isAllowed("ling-a", "storage:sql", AccessType.WRITE)).thenReturn(true);
            when(ps.hasCapabilityPrefix("ling-a", "storage:sql:table:")).thenReturn(true);
            // 表级只有 READ，要求 WRITE 应失败
            when(ps.isAllowed("ling-a", "storage:sql:table:users", AccessType.WRITE)).thenReturn(false);

            SqlPermissionPlan plan = SqlPermissionSupport.analyze("insert into users (id) values (1)");
            ResolvedCapability result = SqlPermissionSupport.resolveCapability(ps, "ling-a", plan);

            assertFalse(result.isAllowed());
            verify(ps).isAllowed("ling-a", "storage:sql:table:users", AccessType.WRITE);
            // 不应用 READ 检查写表
            verify(ps, never()).isAllowed(eq("ling-a"), eq("storage:sql:table:users"), eq(AccessType.READ));
        }

        @Test
        @DisplayName("读表要求 READ 权限，有 READ 应通过")
        void shouldAllowReadTableWhenReadGranted() {
            PermissionService ps = mock(PermissionService.class);
            when(ps.isAllowed("ling-a", "storage:sql", AccessType.READ)).thenReturn(true);
            when(ps.hasCapabilityPrefix("ling-a", "storage:sql:table:")).thenReturn(true);
            when(ps.isAllowed("ling-a", "storage:sql:table:users", AccessType.READ)).thenReturn(true);

            SqlPermissionPlan plan = SqlPermissionSupport.analyze("select * from users");
            ResolvedCapability result = SqlPermissionSupport.resolveCapability(ps, "ling-a", plan);

            assertTrue(result.isAllowed());
            verify(ps).isAllowed("ling-a", "storage:sql:table:users", AccessType.READ);
        }

        @Test
        @DisplayName("INSERT INTO A SELECT FROM B：A 要求 WRITE，B 要求 READ")
        void shouldCheckWriteAndReadSeparatelyForInsertSelect() {
            PermissionService ps = mock(PermissionService.class);
            when(ps.isAllowed("ling-a", "storage:sql", AccessType.WRITE)).thenReturn(true);
            when(ps.hasCapabilityPrefix("ling-a", "storage:sql:table:")).thenReturn(true);
            when(ps.isAllowed("ling-a", "storage:sql:table:target", AccessType.WRITE)).thenReturn(true);
            when(ps.isAllowed("ling-a", "storage:sql:table:source", AccessType.READ)).thenReturn(true);

            SqlPermissionPlan plan = SqlPermissionSupport.analyze(
                    "insert into target (id) select id from source");
            ResolvedCapability result = SqlPermissionSupport.resolveCapability(ps, "ling-a", plan);

            assertTrue(result.isAllowed());
            verify(ps).isAllowed("ling-a", "storage:sql:table:target", AccessType.WRITE);
            verify(ps).isAllowed("ling-a", "storage:sql:table:source", AccessType.READ);
        }

        @Test
        @DisplayName("INSERT INTO A SELECT FROM B：仅有 A 的 WRITE 但缺 B 的 READ 应拒绝")
        void shouldRejectWhenSourceReadNotGranted() {
            PermissionService ps = mock(PermissionService.class);
            when(ps.isAllowed("ling-a", "storage:sql", AccessType.WRITE)).thenReturn(true);
            when(ps.hasCapabilityPrefix("ling-a", "storage:sql:table:")).thenReturn(true);
            when(ps.isAllowed("ling-a", "storage:sql:table:target", AccessType.WRITE)).thenReturn(true);
            when(ps.isAllowed("ling-a", "storage:sql:table:source", AccessType.READ)).thenReturn(false);

            SqlPermissionPlan plan = SqlPermissionSupport.analyze(
                    "insert into target (id) select id from source");
            ResolvedCapability result = SqlPermissionSupport.resolveCapability(ps, "ling-a", plan);

            assertFalse(result.isAllowed());
        }

        @Test
        @DisplayName("解析失败应 fail-closed 拒绝")
        void shouldFailClosedWhenParseFailed() {
            PermissionService ps = mock(PermissionService.class);
            when(ps.isAllowed("ling-a", "storage:sql", AccessType.EXECUTE)).thenReturn(true);

            SqlPermissionPlan plan = SqlPermissionSupport.analyze("not-valid-sql");
            ResolvedCapability result = SqlPermissionSupport.resolveCapability(ps, "ling-a", plan);

            assertFalse(result.isAllowed());
            assertFalse(plan.isParseable());
        }
    }
}
