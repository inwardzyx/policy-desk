-- 测试库（H2, MODE=MySQL）。
-- ★ 与 db/init.sql 的差别只有"去掉 MySQL 专有语法"（ENGINE/CHARSET/KEY 写法），
--   列名与类型必须与生产库一致 —— 否则测试绿了、上线建表失败，是最坏的一种绿。

CREATE TABLE IF NOT EXISTS users (
    id            BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    username      VARCHAR(64)  NOT NULL UNIQUE,
    password_hash VARCHAR(72)  NOT NULL,
    role          VARCHAR(32)  NOT NULL,
    enabled       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS audit (
    id              BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id         BIGINT      NOT NULL,
    question_sha256 CHAR(64)    NOT NULL,
    namespace       VARCHAR(64),
    decision        VARCHAR(32) NOT NULL,
    n_blocks        INT,
    took_ms         INT,
    trace_id        VARCHAR(64),
    created_at      TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
