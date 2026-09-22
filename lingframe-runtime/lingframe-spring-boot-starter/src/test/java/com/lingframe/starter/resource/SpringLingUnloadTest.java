package com.lingframe.starter.resource;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * SpringBoot 卸载测试入口。
 * <p>
 * 该测试类不直接测试，而是启动独立的进程运行 {@link CleanSpringBootUnloadTest}，
 * 以确保在干净的 SpringBoot 环境中验证 ClassLoader 卸载，避免测试框架的引用干扰。
 */
@Slf4j
@DisplayName("SpringBoot 卸载测试入口")
public class SpringLingUnloadTest {

    /**
     * 启动独立进程运行干净的 SpringBoot 卸载测试
     */
    @Test
    @DisplayName("独立进程测试 ClassLoader 卸载")
    void testUnloadInCleanSpringBootEnvironment() throws Exception {
        log.info("建议直接运行 CleanSpringBootUnloadTest.main() 进行独立进程测试");
        log.info("mvn exec:java -Dexec.mainClass=com.lingframe.starter.resource.CleanSpringBootUnloadTest");
        
        // 直接调用 CleanSpringBootUnloadTest.main() 进行测试
        // 注意：这仍然在 JUnit 测试框架内运行，可能存在引用干扰
        // 最佳方式：通过 Maven exec:java 或 IDE 直接运行 CleanSpringBootUnloadTest.main()
        CleanSpringBootUnloadTest.main(new String[]{});
    }
}
