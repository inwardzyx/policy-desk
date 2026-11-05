-- 制度问答台 · 业务库（MySQL 8）
--
-- ★ 只放"必须有状态"的东西：用户、审计、文档元数据。
--   检索/生成的结果**不入库** —— 那是 Python 的活，存进来只会多一份会漂移的副本。
--
-- 建库建用户的命令见 README 的「初始化数据库」一节（root 口令不进代码库）。

CREATE DATABASE IF NOT EXISTS policy_desk
    DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

USE policy_desk;

-- ---------------------------------------------------------------- 用户
CREATE TABLE IF NOT EXISTS users (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    username      VARCHAR(64)  NOT NULL,
    -- 72 是 BCrypt 哈希的固定长度；存明文是事故，存 md5 是同一种事故
    password_hash VARCHAR(72)  NOT NULL,
    role          VARCHAR(32)  NOT NULL,        -- hr / employee（→ 映射成 ACL，见 M4）
    enabled       TINYINT(1)   NOT NULL DEFAULT 1,
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_users_username (username)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- ---------------------------------------------------------------- 审计
-- ★ 只存"访问事实"：谁、什么时候、问了什么（只存 sha256）、决定是什么、花了多久。
--   不存问题原文（PII），也不存答案正文（那是 Python 侧的事）。
CREATE TABLE IF NOT EXISTS audit (
    id             BIGINT      NOT NULL AUTO_INCREMENT,
    user_id        BIGINT      NOT NULL,
    question_sha256 CHAR(64)   NOT NULL,
    namespace      VARCHAR(64) NULL,
    decision       VARCHAR(32) NOT NULL,        -- answered / refused / quota_denied / upstream_error
    n_blocks       INT         NULL,
    took_ms        INT         NULL,
    trace_id       VARCHAR(64) NULL,
    created_at     DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_audit_user_time (user_id, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
