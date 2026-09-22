package com.lingframe.starter.resource;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;

import javax.sql.DataSource;

/**
 * 关闭灵元 BeanFactory 中残留的 DataSource。
 * <p>
 * DataSource 是资源泄漏（连接池不关），不持有 ClassLoader，
 * 但若不关闭会留挂底层数据库连接。
 */
@Slf4j
final class DataSourceCleaner {

    void close(String lingId, ConfigurableListableBeanFactory beanFactory) {
        try {
            for (String name : beanFactory.getBeanNamesForType(DataSource.class, true, false)) {
                Object ds = beanFactory.getSingleton(name);
                if (ds instanceof AutoCloseable) {
                    try {
                        ((AutoCloseable) ds).close();
                        log.info("[{}] Closed DataSource: {}", lingId, name);
                    } catch (Exception e) {
                        log.warn("[{}] Failed to close DataSource {}: {}", lingId, name, e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            log.debug("[{}] DataSource cleanup failed: {}", lingId, e.getMessage());
        }
    }
}
