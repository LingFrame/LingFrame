package com.lingframe;

import com.lingframe.config.OpenApiConfig;
import com.lingframe.controller.LingCoreController;
import com.lingframe.dashboard.config.DashboardAutoConfiguration;
import com.lingframe.dashboard.controller.GovernanceController;
import com.lingframe.dashboard.controller.LingController;
import com.lingframe.service.LingCoreService;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Import;

@EnableCaching
@Import({
        DashboardAutoConfiguration.class,
        LingController.class,
        GovernanceController.class
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
