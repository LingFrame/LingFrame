CREATE TABLE IF NOT EXISTS t_user
(
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    name       VARCHAR(255) NOT NULL,
    email      VARCHAR(255) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO t_user (name, email)
VALUES ('Alice', 'alice@example.com');
INSERT INTO t_user (name, email)
VALUES ('Bob', 'bob@example.com');
