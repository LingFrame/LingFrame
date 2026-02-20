-- 单元核心业务表：配置管理
CREATE TABLE IF NOT EXISTS t_config (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    config_key VARCHAR(255) NOT NULL UNIQUE,
    config_value VARCHAR(1024),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 示例数据
INSERT INTO t_config (config_key, config_value) VALUES ('app.name', 'LingFrame Demo');
INSERT INTO t_config (config_key, config_value) VALUES ('app.version', '1.0.0');
