-- 订单表
CREATE TABLE IF NOT EXISTS t_order
(
    order_id   BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_name  VARCHAR(255),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 示例数据
INSERT INTO t_order (user_name) VALUES ('Alice');
INSERT INTO t_order (user_name) VALUES ('Bob');
