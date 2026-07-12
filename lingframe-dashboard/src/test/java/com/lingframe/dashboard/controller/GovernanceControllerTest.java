package com.lingframe.dashboard.controller;

import com.lingframe.api.config.GovernancePolicy;
import com.lingframe.dashboard.dto.ApiResponse;
import com.lingframe.dashboard.dto.InvocationGovernanceDTO;
import com.lingframe.dashboard.dto.ResourcePermissionDTO;
import com.lingframe.dashboard.service.DashboardService;
import com.lingframe.core.governance.GovernanceAdminService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 治理控制器测试
 * 覆盖 6 个端点的正常路径与异常路径
 */
@DisplayName("治理控制器测试")
class GovernanceControllerTest {

    private GovernanceAdminService governanceAdmin;
    private DashboardService dashboardService;
    private GovernanceController controller;

    @BeforeEach
    void setUp() {
        governanceAdmin = mock(GovernanceAdminService.class);
        dashboardService = mock(DashboardService.class);
        controller = new GovernanceController(governanceAdmin, dashboardService);
    }

    @Nested
    @DisplayName("getRules")
    class GetRulesTests {
        @Test
        @DisplayName("正常返回所有治理规则")
        void shouldReturnAllRules() {
            Map<String, GovernancePolicy> rules = new HashMap<>();
            rules.put("ling1", new GovernancePolicy());
            when(governanceAdmin.getAllPatches()).thenReturn(rules);

            ApiResponse<Map<String, GovernancePolicy>> response = controller.getRules();

            assertTrue(response.isSuccess());
            assertTrue(response.getData().containsKey("ling1"));
        }

        @Test
        @DisplayName("registry 抛异常时返回 error")
        void shouldReturnErrorOnException() {
            when(governanceAdmin.getAllPatches()).thenThrow(new RuntimeException("db error"));

            ApiResponse<Map<String, GovernancePolicy>> response = controller.getRules();

            assertFalse(response.isSuccess());
            assertTrue(response.getMessage().contains("获取规则失败"));
        }
    }

    @Nested
    @DisplayName("getPatch")
    class GetPatchTests {
        @Test
        @DisplayName("正常返回指定灵元的策略")
        void shouldReturnPatch() {
            GovernancePolicy policy = new GovernancePolicy();
            when(governanceAdmin.getPatchForUpdate("ling1")).thenReturn(policy);

            ApiResponse<GovernancePolicy> response = controller.getPatch("ling1");

            assertTrue(response.isSuccess());
            assertSame(policy, response.getData());
        }

        @Test
        @DisplayName("registry 抛异常时返回 error")
        void shouldReturnErrorOnException() {
            when(governanceAdmin.getPatchForUpdate("ling1")).thenThrow(new RuntimeException("not found"));

            ApiResponse<GovernancePolicy> response = controller.getPatch("ling1");

            assertFalse(response.isSuccess());
            assertTrue(response.getMessage().contains("获取策略失败"));
        }
    }

    @Nested
    @DisplayName("updatePatch")
    class UpdatePatchTests {
        @Test
        @DisplayName("正常更新策略后返回更新后的策略")
        void shouldUpdateAndReturnPatch() {
            GovernancePolicy policy = new GovernancePolicy();
            GovernancePolicy updated = new GovernancePolicy();
            when(governanceAdmin.getPatchForUpdate("ling1")).thenReturn(updated);

            ApiResponse<GovernancePolicy> response = controller.updatePatch("ling1", policy);

            assertTrue(response.isSuccess());
            assertSame(updated, response.getData());
            verify(dashboardService).updateGovernancePolicy("ling1", policy);
        }

        @Test
        @DisplayName("dashboardService 抛异常时返回 error")
        void shouldReturnErrorOnException() {
            GovernancePolicy policy = new GovernancePolicy();
            doThrow(new RuntimeException("update failed"))
                    .when(dashboardService).updateGovernancePolicy("ling1", policy);

            ApiResponse<GovernancePolicy> response = controller.updatePatch("ling1", policy);

            assertFalse(response.isSuccess());
            assertTrue(response.getMessage().contains("策略更新失败"));
        }
    }

    @Nested
    @DisplayName("getInvocationGovernance")
    class GetInvocationGovernanceTests {
        @Test
        @DisplayName("正常返回调用治理配置")
        void shouldReturnInvocationGovernance() {
            InvocationGovernanceDTO dto = new InvocationGovernanceDTO();
            when(dashboardService.getInvocationGovernance("ling1")).thenReturn(dto);

            ApiResponse<InvocationGovernanceDTO> response = controller.getInvocationGovernance("ling1");

            assertTrue(response.isSuccess());
            assertSame(dto, response.getData());
        }

        @Test
        @DisplayName("dashboardService 抛异常时返回 error")
        void shouldReturnErrorOnException() {
            when(dashboardService.getInvocationGovernance("ling1"))
                    .thenThrow(new RuntimeException("failed"));

            ApiResponse<InvocationGovernanceDTO> response = controller.getInvocationGovernance("ling1");

            assertFalse(response.isSuccess());
            assertTrue(response.getMessage().contains("获取调用治理失败"));
        }
    }

    @Nested
    @DisplayName("updateInvocationGovernance")
    class UpdateInvocationGovernanceTests {
        @Test
        @DisplayName("正常更新调用治理配置后返回更新结果")
        void shouldUpdateAndReturnInvocationGovernance() {
            InvocationGovernanceDTO dto = new InvocationGovernanceDTO();
            InvocationGovernanceDTO updated = new InvocationGovernanceDTO();
            when(dashboardService.updateInvocationGovernance("ling1", dto)).thenReturn(updated);

            ApiResponse<InvocationGovernanceDTO> response = controller.updateInvocationGovernance("ling1", dto);

            assertTrue(response.isSuccess());
            assertSame(updated, response.getData());
        }

        @Test
        @DisplayName("dashboardService 抛异常时返回 error")
        void shouldReturnErrorOnException() {
            InvocationGovernanceDTO dto = new InvocationGovernanceDTO();
            when(dashboardService.updateInvocationGovernance("ling1", dto))
                    .thenThrow(new RuntimeException("failed"));

            ApiResponse<InvocationGovernanceDTO> response = controller.updateInvocationGovernance("ling1", dto);

            assertFalse(response.isSuccess());
            assertTrue(response.getMessage().contains("调用治理更新失败"));
        }
    }

    @Nested
    @DisplayName("updatePermissions")
    class UpdatePermissionsTests {
        @Test
        @DisplayName("正常更新权限后返回传入的 dto")
        void shouldUpdatePermissions() {
            ResourcePermissionDTO dto = new ResourcePermissionDTO();

            ApiResponse<ResourcePermissionDTO> response = controller.updatePermissions("ling1", dto);

            assertTrue(response.isSuccess());
            assertSame(dto, response.getData());
            verify(dashboardService).updatePermissions("ling1", dto);
        }

        @Test
        @DisplayName("dashboardService 抛异常时返回 error")
        void shouldReturnErrorOnException() {
            ResourcePermissionDTO dto = new ResourcePermissionDTO();
            doThrow(new RuntimeException("denied"))
                    .when(dashboardService).updatePermissions("ling1", dto);

            ApiResponse<ResourcePermissionDTO> response = controller.updatePermissions("ling1", dto);

            assertFalse(response.isSuccess());
            assertTrue(response.getMessage().contains("权限更新失败"));
        }
    }
}
