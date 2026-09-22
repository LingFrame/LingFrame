package com.lingframe.dashboard.controller;

import com.lingframe.dashboard.dto.ApiResponse;
import com.lingframe.dashboard.dto.LingInfoDTO;
import com.lingframe.dashboard.dto.LingPackageDTO;
import com.lingframe.dashboard.service.DashboardService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("灵元磁盘包控制器测试")
class PackageControllerTest {

    @Nested
    @DisplayName("扫描磁盘包列表")
    class ListPackages {

        @Test
        @DisplayName("应返回 service 层扫描结果")
        void shouldReturnServiceResult() {
            DashboardService dashboardService = mock(DashboardService.class);
            LingPackageDTO pkg = LingPackageDTO.builder()
                    .lingId("user-ling")
                    .version("1.0.0")
                    .fileName("user-ling-1.0.0.jar")
                    .fileSize(1024L)
                    .mainClass("com.example.UserLing")
                    .isInstalled(false)
                    .permissions(Collections.singletonList("READ"))
                    .build();
            when(dashboardService.scanPackages()).thenReturn(Collections.singletonList(pkg));

            PackageController controller = new PackageController(dashboardService);
            ApiResponse<List<LingPackageDTO>> resp = controller.listPackages();

            assertTrue(resp.isSuccess());
            assertNotNull(resp.getData());
            assertEquals(1, resp.getData().size());
            assertEquals("user-ling", resp.getData().get(0).getLingId());
        }

        @Test
        @DisplayName("service 抛异常时应返回失败响应而非传播异常")
        void shouldReturnErrorOnException() {
            DashboardService dashboardService = mock(DashboardService.class);
            when(dashboardService.scanPackages()).thenThrow(new RuntimeException("disk error"));

            PackageController controller = new PackageController(dashboardService);
            ApiResponse<List<LingPackageDTO>> resp = controller.listPackages();

            assertFalse(resp.isSuccess());
            assertTrue(resp.getMessage().contains("扫描磁盘包失败"));
        }
    }

    @Nested
    @DisplayName("部署磁盘包")
    class DeployPackage {

        @Test
        @DisplayName("合法 lingId 与 version 应透传到 service 并返回成功")
        void shouldDelegateToServiceOnValidInput() {
            DashboardService dashboardService = mock(DashboardService.class);
            LingInfoDTO info = LingInfoDTO.builder()
                    .lingId("user-ling")
                    .status("ACTIVE")
                    .installedAt(System.currentTimeMillis())
                    .build();
            when(dashboardService.deployPackage("user-ling", "1.0.0")).thenReturn(info);

            PackageController controller = new PackageController(dashboardService);
            PackageController.DeployRequest req = new PackageController.DeployRequest();
            req.setLingId("user-ling");
            req.setVersion("1.0.0");

            ApiResponse<LingInfoDTO> resp = controller.deployPackage(req);

            assertTrue(resp.isSuccess());
            assertEquals("user-ling", resp.getData().getLingId());
        }

        @Test
        @DisplayName("lingId 为空应被输入校验拦截，不进入 service")
        void shouldRejectEmptyLingId() {
            DashboardService dashboardService = mock(DashboardService.class);
            PackageController controller = new PackageController(dashboardService);

            PackageController.DeployRequest req = new PackageController.DeployRequest();
            req.setLingId("");
            req.setVersion("1.0.0");

            ApiResponse<LingInfoDTO> resp = controller.deployPackage(req);
            assertFalse(resp.isSuccess());
            assertTrue(resp.getMessage().contains("lingId 不能为空"));
        }

        @Test
        @DisplayName("version 为 null 应被输入校验拦截")
        void shouldRejectNullVersion() {
            DashboardService dashboardService = mock(DashboardService.class);
            PackageController controller = new PackageController(dashboardService);

            PackageController.DeployRequest req = new PackageController.DeployRequest();
            req.setLingId("user-ling");
            req.setVersion(null);

            ApiResponse<LingInfoDTO> resp = controller.deployPackage(req);
            assertFalse(resp.isSuccess());
            assertTrue(resp.getMessage().contains("version 不能为空"));
        }

        @Test
        @DisplayName("请求体为 null 应被输入校验拦截")
        void shouldRejectNullRequest() {
            DashboardService dashboardService = mock(DashboardService.class);
            PackageController controller = new PackageController(dashboardService);

            ApiResponse<LingInfoDTO> resp = controller.deployPackage(null);
            assertFalse(resp.isSuccess());
            assertTrue(resp.getMessage().contains("请求体为空"));
        }

        @Test
        @DisplayName("lingId 含路径穿越字符 ../ 应被格式校验拦截")
        void shouldRejectPathTraversalLingId() {
            DashboardService dashboardService = mock(DashboardService.class);
            PackageController controller = new PackageController(dashboardService);

            PackageController.DeployRequest req = new PackageController.DeployRequest();
            req.setLingId("../evil");
            req.setVersion("1.0.0");

            ApiResponse<LingInfoDTO> resp = controller.deployPackage(req);
            assertFalse(resp.isSuccess());
            assertTrue(resp.getMessage().contains("lingId 格式非法"));
        }

        @Test
        @DisplayName("lingId 含斜杠分隔符应被格式校验拦截")
        void shouldRejectSlashInLingId() {
            DashboardService dashboardService = mock(DashboardService.class);
            PackageController controller = new PackageController(dashboardService);

            PackageController.DeployRequest req = new PackageController.DeployRequest();
            req.setLingId("a/b");
            req.setVersion("1.0.0");

            ApiResponse<LingInfoDTO> resp = controller.deployPackage(req);
            assertFalse(resp.isSuccess());
            assertTrue(resp.getMessage().contains("lingId 格式非法"));
        }

        @Test
        @DisplayName("version 含路径穿越字符 \\ 应被格式校验拦截")
        void shouldRejectPathTraversalVersion() {
            DashboardService dashboardService = mock(DashboardService.class);
            PackageController controller = new PackageController(dashboardService);

            PackageController.DeployRequest req = new PackageController.DeployRequest();
            req.setLingId("user-ling");
            req.setVersion("..\\evil");

            ApiResponse<LingInfoDTO> resp = controller.deployPackage(req);
            assertFalse(resp.isSuccess());
            assertTrue(resp.getMessage().contains("version 格式非法"));
        }

        @Test
        @DisplayName("service 抛异常时应返回失败响应而非传播异常")
        void shouldReturnErrorOnServiceException() {
            DashboardService dashboardService = mock(DashboardService.class);
            when(dashboardService.deployPackage("user-ling", "1.0.0"))
                    .thenThrow(new RuntimeException("package not found"));

            PackageController controller = new PackageController(dashboardService);
            PackageController.DeployRequest req = new PackageController.DeployRequest();
            req.setLingId("user-ling");
            req.setVersion("1.0.0");

            ApiResponse<LingInfoDTO> resp = controller.deployPackage(req);
            assertFalse(resp.isSuccess());
            assertTrue(resp.getMessage().contains("部署失败"));
        }
    }
}
