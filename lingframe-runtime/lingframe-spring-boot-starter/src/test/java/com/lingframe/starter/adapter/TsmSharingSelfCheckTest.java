package com.lingframe.starter.adapter;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.lingframe.api.storage.ManagedDataSourceRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.mock.env.MockEnvironment;

import javax.sql.DataSource;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * TSM 共享启动期自检测试：穿透地基 = 灵核与灵元共享同一份
 * TransactionSynchronizationManager（spring-tx 父委派）。
 * <p>
 * 经 {@link LingDataSourceRegistrar#register} 分支 B 公开路径触发自检（装配接线验证），
 * 用 Logback {@link ListAppender} 捕获 WARN 断言三分支：
 * 一致（父委派共享，无 WARN）/ 不一致（两栈分叉，输出 WARN）/ 类缺失（无 spring-tx，跳过不误报）。
 */
@DisplayName("TSM 共享启动期自检")
class TsmSharingSelfCheckTest {

    private static final String TSM_CLASS_NAME = "org.springframework.transaction.support.TransactionSynchronizationManager";
    private static final String WARN_MARKER = "TransactionSynchronizationManager NOT shared";

    private Logger registrarLogger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attachAppender() {
        registrarLogger = (Logger) LoggerFactory.getLogger(LingDataSourceRegistrar.class);
        appender = new ListAppender<>();
        appender.start();
        registrarLogger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        registrarLogger.detachAppender(appender);
        registrarLogger.setLevel(Level.INFO);
    }

    private void registerWith(ClassLoader lingClassLoader) {
        GenericApplicationContext context = new GenericApplicationContext();
        context.setEnvironment(new MockEnvironment());

        ManagedDataSourceRegistry registry = mock(ManagedDataSourceRegistry.class);
        DataSource managed = mock(DataSource.class);
        when(registry.lookup("default")).thenReturn(managed);

        LingDataSourceRegistrar.register(context, lingClassLoader, "demo-ling", registry);
    }

    private List<ILoggingEvent> warnEvents() {
        return appender.list.stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .filter(event -> event.getFormattedMessage().contains(WARN_MARKER))
                .collect(Collectors.toList());
    }

    @Nested
    @DisplayName("一致分支：spring-tx 父委派共享")
    class Shared {

        @Test
        @DisplayName("灵元 ClassLoader 与灵核同源解析 TSM → 无 WARN（穿透地基成立）")
        void noWarnWhenTsmShared() {
            // 测试类加载器与 LingDataSourceRegistrar 同源（app classpath 共享 spring-tx）
            registerWith(TsmSharingSelfCheckTest.class.getClassLoader());

            assertThat(warnEvents()).isEmpty();
        }
    }

    @Nested
    @DisplayName("不一致分支：两栈分叉")
    class Forked {

        @Test
        @DisplayName("灵元 ClassLoader 独立加载第二份 TSM → 输出 WARN（父委派配置错误可观测）")
        void warnWhenTsmForked() throws Exception {
            // 从运行期定位 spring-tx jar，构造隔离 ClassLoader 独立加载 TSM（模拟父委派断裂）
            URL springTxJar = Class.forName(TSM_CLASS_NAME)
                    .getProtectionDomain().getCodeSource().getLocation();
            ClassLoader isolated = new URLClassLoader(new URL[]{springTxJar}, null);

            registerWith(isolated);

            assertThat(warnEvents()).hasSize(1);
            // WARN 消息携带短类名（两栈分叉）与灵元 ID（归因）
            assertThat(warnEvents().get(0).getFormattedMessage())
                    .contains("TransactionSynchronizationManager NOT shared")
                    .contains("demo-ling");
        }
    }

    @Nested
    @DisplayName("类缺失分支：无 spring-tx 环境")
    class Missing {

        @Test
        @DisplayName("灵元 ClassLoader 解析不到 TSM → 跳过检测不误报（无 WARN）")
        void noWarnWhenTsmUnresolvable() throws Exception {
            // 空 classpath 隔离 ClassLoader：无法解析 TSM（模拟无 spring-tx 环境）
            ClassLoader empty = new URLClassLoader(new URL[0], null);

            registerWith(empty);

            assertThat(warnEvents()).isEmpty();
        }
    }
}
