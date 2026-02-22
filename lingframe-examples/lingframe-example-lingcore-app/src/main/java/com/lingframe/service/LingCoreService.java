package com.lingframe.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class LingCoreService {

    private final JdbcTemplate jdbcTemplate;

    public String sayHello() {
        return "Hello, LINGCORE!";
    }

    /**
     * 查询配置（带缓存）
     */
    @Cacheable(cacheNames = "configs", key = "#key")
    public String getConfig(String key) {
        log.info("getConfig (cache miss), key: {}", key);
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT config_value FROM t_config WHERE config_key = ?",
                    String.class, key);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 设置配置（更新缓存）
     */
    @CachePut(cacheNames = "configs", key = "#key")
    public String setConfig(String key, String value) {
        log.info("setConfig, key: {}, value: {}", key, value);
        int updated = jdbcTemplate.update(
                "UPDATE t_config SET config_value = ? WHERE config_key = ?",
                value, key);
        if (updated == 0) {
            jdbcTemplate.update(
                    "INSERT INTO t_config (config_key, config_value) VALUES (?, ?)",
                    key, value);
        }
        return value;
    }

    /**
     * 删除配置（清除缓存）
     */
    @CacheEvict(cacheNames = "configs", key = "#key")
    public boolean deleteConfig(String key) {
        log.info("deleteConfig, key: {}", key);
        return jdbcTemplate.update("DELETE FROM t_config WHERE config_key = ?", key) > 0;
    }

    /**
     * 列出所有配置
     */
    public List<Map<String, Object>> listConfigs() {
        log.info("listConfigs");
        return jdbcTemplate.queryForList("SELECT * FROM t_config");
    }
}
