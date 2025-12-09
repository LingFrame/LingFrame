package com.lingframe;

import com.lingframe.core.config.LingFrameConfig;
import com.lingframe.core.plugin.PluginManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.io.File;

@Slf4j
@Component
@RequiredArgsConstructor
public class DevLoader implements CommandLineRunner {

    private final PluginManager pluginManager;

    @Override
    public void run(String... args) throws Exception {
        log.info("DevLoader.run mode = {}", LingFrameConfig.isDevMode());
        // 仅在开发环境执行
        if (!LingFrameConfig.isDevMode()) return;

        // 指向插件的编译输出目录 (IDEA 默认是 target/classes)
        // 注意：这里是硬盘的绝对路径
        File userPluginDir = new File("lingframe-samples/lingframe-sample-plugin-user/target/classes");
        log.info("DevLoader.run: userPluginDir = {}", userPluginDir.getAbsolutePath());

        log.warn("啊啊啊啊");
        if (userPluginDir.exists()) {
            // 安装开发版插件
            pluginManager.installDev("user-plugin", "dev-snapshot", userPluginDir);
            log.info("🔥🔥🔥 开发模式插件已加载，文件监听已开启！请尝试修改代码并重新编译(Ctrl+F9)。");
        } else {
            log.warn("DevLoader.run: userPluginDir not exists, please check the path");
        }
    }
}
