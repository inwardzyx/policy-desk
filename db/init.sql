-- 制度问答台 · 业务库（MySQL 8）
--
-- ★ 只放"必须有状态"的东西：用户、审计、文档元数据。
--   检索/生成的结果**不入库** —— 那是 Python 的活，存进来只会多一份会漂移的副本。
--
-- ★ 这个文件故意**不含 `CREATE DATABASE` / `USE`**：
--   建库和建用户需要 root（见 README「初始化数据库」），而这份 DDL 是由
--   `policy` 用户在自己的库里执行的 —— 它没有全局 CREATE 权限。
--   早期版本把 `CREATE DATABASE` 写在第一句，结果 policy 用户一执行就报
--   "Access denied"，**后面所有建表语句全被跳过**，而且没有输出、看起来像成功了。
--   ⇒ 权限边界要按"谁来执行"切，别把 root 的活混进应用账号的脚本。

-- ---------------------------------------------------------------- 用户
CREATE TABLE IF NOT EXISTS users (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    username      VARCHAR(64)  NOT NULL,
    -- BCrypt 的输出长度是**固定 60 字符**（$2a$10$ + 22 位 salt + 31 位摘要）。
    -- ★ 这里原来注释写的是 72，实测 60 —— 改成 72 是为了留余量（未来换更强算法），
    --   但注释必须写真实值，否则下一个人会按 72 去校验长度，把 60 位哈希当成"被截断"。
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
