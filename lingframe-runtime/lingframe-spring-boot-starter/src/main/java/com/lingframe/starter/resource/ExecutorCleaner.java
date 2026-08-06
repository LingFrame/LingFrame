package com.lingframe.starter.resource;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;

import java.util.concurrent.ExecutorService;

/**
 * 关闭灵元 BeanFactory 中残留的 ExecutorService。
 * <p>
 * ExecutorService 不关闭会留挂工作线程，工作线程的 contextClassLoader
 * 可能指向灵元 ClassLoader，阻止 GC。
 */
@Slf4j
final class ExecutorCleaner {

    void shutdown(String lingId, ConfigurableListableBeanFactory beanFactory) {
        try {
            String[] names = beanFactory.getBeanNamesForType(ExecutorService.class);
            for (String name : names) {
                try {
                    ExecutorService executor = beanFactory.getBean(name, ExecutorService.class);
                    if (!executor.isShutdown()) {
                        executor.shutdownNow();
                        log.info("[{}] Shut down ExecutorService: {}", lingId, name);
                    }
                } catch (Exception e) {
                    log.debug("[{}] Failed to shutdown executor {}: {}", lingId, name, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.debug("[{}] Executor cleanup failed: {}", lingId, e.getMessage());
        }
    }
}
