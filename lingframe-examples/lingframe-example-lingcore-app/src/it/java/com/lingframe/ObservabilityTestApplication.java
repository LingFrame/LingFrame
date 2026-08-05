package com.lingframe;

import com.lingframe.config.OpenApiConfig;
import com.lingframe.controller.LingCoreController;
import com.lingframe.dashboard.config.DashboardAutoConfiguration;
import com.lingframe.dashboard.controller.GovernanceController;
import com.lingframe.dashboard.controller.LingController;
import com.lingframe.dashboard.controller.MetricsController;
import com.lingframe.service.LingCoreService;
import com.lingframe.starter.configuration.LingFrameCoreConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Import;

@EnableCaching
@Import({
        LingFrameCoreConfiguration.class,
        DashboardAutoConfiguration.class,
        LingController.class,
        GovernanceController.class,
        MetricsController.class
})
@SpringBootApplication(
        exclude = RedisAutoConfiguration.class,
        scanBasePackageClasses = {
                LingCoreController.class,
                LingCoreService.class,
                OpenApiConfig.class
        }
)
class ObservabilityTestApplication {
}
