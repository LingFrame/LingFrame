package com.lingframe.api.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * API 安全模块测试
 */
class SecurityApiTest {

    @Nested
    @DisplayName("PermissionAuditResult")
    class PermissionAuditResultTest {

        @Test
        @DisplayName("ALLOWED 为成功状态")
        void allowedShouldBeSuccess() {
            assertTrue(PermissionAuditResult.ALLOWED.isSuccess());
        }

        @Test
        @DisplayName("DENIED 和 FAILED 为失败状态")
        void deniedAndFailedShouldNotBeSuccess() {
            assertFalse(PermissionAuditResult.DENIED.isSuccess());
            assertFalse(PermissionAuditResult.FAILED.isSuccess());
        }

        @Test
        @DisplayName("枚举值完整")
        void shouldHaveAllValues() {
            assertEquals(3, PermissionAuditResult.values().length);
            assertNotNull(PermissionAuditResult.valueOf("ALLOWED"));
            assertNotNull(PermissionAuditResult.valueOf("DENIED"));
            assertNotNull(PermissionAuditResult.valueOf("FAILED"));
        }
    }

    @Nested
    @DisplayName("AccessType")
    class AccessTypeTest {

        @Test
        @DisplayName("权限层级：NONE < READ < WRITE < EXECUTE")
        void shouldHaveCorrectHierarchy() {
            assertTrue(AccessType.WRITE.satisfies(AccessType.READ));
            assertTrue(AccessType.EXECUTE.satisfies(AccessType.WRITE));
            assertTrue(AccessType.EXECUTE.satisfies(AccessType.READ));
            assertFalse(AccessType.READ.satisfies(AccessType.WRITE));
            assertFalse(AccessType.NONE.satisfies(AccessType.NONE));
            assertFalse(AccessType.NONE.satisfies(AccessType.READ));
        }

        @Test
        @DisplayName("isAtLeast 层级比较")
        void shouldCompareLevel() {
            assertTrue(AccessType.WRITE.isAtLeast(AccessType.READ));
            assertTrue(AccessType.WRITE.isAtLeast(AccessType.WRITE));
            assertFalse(AccessType.READ.isAtLeast(AccessType.WRITE));
        }

        @Test
        @DisplayName("max/min 取高低权限")
        void shouldGetMaxAndMin() {
            assertEquals(AccessType.WRITE, AccessType.READ.max(AccessType.WRITE));
            assertEquals(AccessType.READ, AccessType.READ.min(AccessType.WRITE));
        }

        @Test
        @DisplayName("getLevel 返回正确数值")
        void shouldReturnCorrectLevel() {
            assertEquals(0, AccessType.NONE.getLevel());
            assertEquals(1, AccessType.READ.getLevel());
            assertEquals(2, AccessType.WRITE.getLevel());
            assertEquals(3, AccessType.EXECUTE.getLevel());
        }
    }

    @Nested
    @DisplayName("PermissionAuditRecord")
    class PermissionAuditRecordTest {

        @Test
        @DisplayName("Builder 构造完整记录")
        void shouldBuildRecord() {
            PermissionAuditRecord record = PermissionAuditRecord.builder()
                    .callerLingId("order-ling")
                    .principal("admin")
                    .capability("db")
                    .action("write")
                    .resource("orders")
                    .result(PermissionAuditResult.ALLOWED)
                    .failureReason(null)
                    .costNanos(12345L)
                    .build();

            assertEquals("order-ling", record.getCallerLingId());
            assertEquals("admin", record.getPrincipal());
            assertEquals("db", record.getCapability());
            assertEquals("write", record.getAction());
            assertEquals("orders", record.getResource());
            assertEquals(PermissionAuditResult.ALLOWED, record.getResult());
            assertNull(record.getFailureReason());
            assertEquals(12345L, record.getCostNanos());
        }
    }

    @Nested
    @DisplayName("PermissionInfo")
    class PermissionInfoTest {

        @Test
        @DisplayName("permanent 创建永不过期权限")
        void shouldCreatePermanentPermission() {
            PermissionInfo info = PermissionInfo.permanent("order-ling", "storage:sql", AccessType.WRITE, "ling.yml");
            assertEquals("order-ling", info.getLingId());
            assertEquals("storage:sql", info.getCapability());
            assertEquals(AccessType.WRITE, info.getAccessType());
            assertEquals("ling.yml", info.getSource());
            assertNotNull(info.getGrantedAt());
            assertFalse(info.isExpired());
        }

        @Test
        @DisplayName("withExpiry 创建有过期时间的权限")
        void shouldCreateExpiringPermission() {
            java.time.Instant past = java.time.Instant.now().minusSeconds(60);
            PermissionInfo info = PermissionInfo.withExpiry("order-ling", "cache:redis", AccessType.READ, past, "runtime-grant");
            assertTrue(info.isExpired());
        }

        @Test
        @DisplayName("satisfies 检查权限是否满足需求")
        void shouldCheckSatisfies() {
            PermissionInfo info = PermissionInfo.permanent("order-ling", "storage:sql", AccessType.WRITE, "ling.yml");
            assertTrue(info.satisfies(AccessType.READ));
            assertTrue(info.satisfies(AccessType.WRITE));
            assertFalse(info.satisfies(AccessType.EXECUTE));
        }

        @Test
        @DisplayName("过期权限不满足任何需求")
        void shouldNotSatisfyWhenExpired() {
            java.time.Instant past = java.time.Instant.now().minusSeconds(60);
            PermissionInfo info = PermissionInfo.withExpiry("order-ling", "storage:sql", AccessType.WRITE, past, "runtime");
            assertFalse(info.satisfies(AccessType.READ));
        }
    }

    @Nested
    @DisplayName("Capabilities")
    class CapabilitiesTest {

        @Test
        @DisplayName("预定义能力常量")
        void shouldHavePredefinedCapabilities() {
            assertNotNull(Capabilities.STORAGE_SQL);
            assertNotNull(Capabilities.CACHE_LOCAL);
            assertNotNull(Capabilities.CACHE_REDIS);
            assertNotNull(Capabilities.NETWORK_HTTP);
            assertNotNull(Capabilities.NETWORK_RPC);
            assertNotNull(Capabilities.FILE_READ);
            assertNotNull(Capabilities.FILE_WRITE);
            assertNotNull(Capabilities.IPC_INVOKE);
        }
    }

    @Nested
    @DisplayName("AuditMetadataKeys")
    class AuditMetadataKeysTest {

        @Test
        @DisplayName("预定义审计键")
        void shouldHavePredefinedKeys() {
            assertNotNull(AuditMetadataKeys.PRINCIPAL);
            assertTrue(AuditMetadataKeys.PRINCIPAL.startsWith("audit."));
        }
    }
}
