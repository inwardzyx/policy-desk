# 制度问答台 · policy-desk

> 一个**能证明自己没变坏**的企业制度问答系统。
> 每条答案带可核验出处，答不出就明确拒答，改动发布前必须过评测门禁。

**当前进度：M4 完成**（登录 → JWT → 角色 → **ACL 投影** → 转给 Python AI 服务）。

---

## 为什么是三层（而不是一个 Python 服务全干）

```text
浏览器  ──JWT──▶  Vue3（apps/web，M6）
                     │
                     ├──▶  Spring Boot（apps/api）  用户 / 角色 / 配额 / 审计 / 会话
                     │            │  HTTP + X-Internal-Token + X-Auth-Roles
                     │            ▼
                     └──▶  Python AI 服务（另一个仓库）  检索 / 精排 / 生成 / 评测
```

判断一段逻辑放哪边，只问一句：**它需不需要记住「上一次」？**
需要 ⇒ Java（有状态，要事务/持久化）；不需要 ⇒ Python（无状态，好重启、好扩容）。

直接后果：**配额用尽的请求在 Java 就被 429 掉，不会打到模型，也就不烧 token。**

---

## 初始化数据库（一次性）

root 口令不写进代码库。用本机 MySQL 客户端执行下面这段（**把 `你的口令` 换成 root 口令**）：

```powershell
$mysql = "C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe"
& $mysql -u root -p --default-character-set=utf8mb4 -e "CREATE DATABASE IF NOT EXISTS policy_desk DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci; CREATE USER IF NOT EXISTS 'policy'@'localhost' IDENTIFIED BY 'policy_dev_2026'; GRANT ALL PRIVILEGES ON policy_desk.* TO 'policy'@'localhost'; FLUSH PRIVILEGES;"
& $mysql -u policy -ppolicy_dev_2026 policy_desk --default-character-set=utf8mb4 -e "source db/init.sql"
```

★ `db/init.sql` **不含种子账号**：演示账号由 `DemoUserSeeder` 在运行时用 BCrypt 现算插入，
且默认关闭（`SEED_DEMO_USERS=true` 才开，且用户表为空时才插）。
把哈希写死进 SQL 的那一刻，"演示口令"就成了一条永久后门。

---

## 怎么跑（三个进程）

```powershell
# 1) Python AI 服务（另一个仓库，端口 8028）
$env:INTERNAL_TOKEN="dev-token"; $env:AUTH_MODE="proxy"; $env:TRUST_PROXY_HEADERS="1"
cd D:\_review_rag-cs
D:\Python-project\.venv\Scripts\python.exe -m uvicorn service:app --port 8028

# 2) Java 业务层（端口 8081）
cd D:\policy-desk\apps\api
$env:INTERNAL_TOKEN="dev-token"
$env:PYTHON_AI_URL="http://127.0.0.1:8028"
$env:DB_USER="policy"; $env:DB_PASSWORD="policy_dev_2026"
$env:JWT_SECRET="dev-secret-please-change-0123456789abcdef"   # >= 32 字节
$env:SEED_DEMO_USERS="true"; $env:DEMO_PASSWORD="policy123"
D:\idea\bin\java.exe -jar target\policy-desk-api-0.1.0-SNAPSHOT.jar
```

构建（本机没装 Maven，仓库自带一份在 `tools/` 下）：

```powershell
$env:JAVA_HOME="D:\idea"
D:\policy-desk\tools\apache-maven-3.9.9\bin\mvn.cmd -s D:\policy-desk\tools\settings.xml -f apps\api\pom.xml test
```

---

## 验证（2026-11-05 实测，非估算）

### Java 侧（H2 内存库，42 条）

```text
Tests run: 42, Failures: 0, Errors: 0, Skipped: 0
```

| 判据 | 证明什么 |
|---|---|
| `AclProjectorTest` 6 条 | 三个角色的映射；多角色取并集；**输出顺序固定**；未知角色/空角色**抛错而不是降级** |
| `JwtServiceTest` 5 条 | 令牌签得出/验得回；**过期拒**；**改一个字符拒**；**换密钥拒**；密钥 <32 字节**构造即失败** |
| `JwtAuthFilterTest` 4 条 | 无令牌放行但不设身份；**坏令牌 401 且不再往下走**；好令牌写入身份；`/actuator/**` 不过滤 |
| `AuthServiceTest` 4 条 | 口令对给令牌；**口令错／用户不存在／被禁用返回同一个错误**（不给账号枚举器留口子） |
| `ChatControllerTest` 9 条 | **发给 AI 的是 ACL 不是角色名**；employee 只拿到 `public`；未知角色 403 **且不调用 AI**；匿名 401 **且不调用 AI**；已认证时 `X-Demo-Roles` 无效；空问题 400；502/504 分开 |
| `AuthControllerTest` 3 条 | 登录成功给 token；失败两种情形**不可区分**；空输入 400 |
| `LoginIntegrationTest` 4 条 | 种子账号→登录→`/api/me` 全链路；**健康检查在上游不可用时真的报红** |
| `PythonAiClientTest` 3 条 | 两个头发对；上游 401 变可判断异常；上游多字段不炸解析 |
| `PythonAiPropertiesTest` 3 条 | 缺 token/base-url **构造即失败**；超时非正数退回默认 |

### ACL 隔离（M4 端到端，2026-11-05 实测）

准备：往 `acltest` 分区上传一篇 **`acl=hr`** 的文档（「一线城市出差住宿补贴上限 800 元」），
然后两个账号问**同一个问题** `namespace=acltest`：

| 账号 | 角色 → ACL | knowledge_hit | sources | 答案 |
|---|---|---|---|---|
| `hr_demo` | hr → `hr,public` | **true** | 1（`HR专用薪酬条款`） | 「一线城市出差住宿补贴上限为每晚 800 元，需提供发票报销。[1]」 |
| `emp_demo` | employee → `public` | **false** | 0 | 「知识库里没有能回答这个问题的资料，我不编。」 |

★ 这一对是 M4 的全部意义：**同一个问题、同一个分区、同一个模型**，
差别只来自令牌里的角色。测试文档事后已删除（`deleted:true`），分区已清空。

### 跨服务（M2 那轮，真打 DeepSeek）

| 检查 | 结果 |
|---|---|
| `GET /actuator/health` | `pythonAi: UP`（`chunks=332`） |
| `POST /api/chat`「学生违纪处分有哪些等级？」 | 4886ms，`knowledge_hit=true`，引用 1 条 |
| 库外问题 | `knowledge_hit=false`、答案「知识库里没有…我不编。」 |
| 无口令直连 Python `/internal/chat` | 401 |

---

## 里程碑

| 里程碑 | 内容 | 状态 |
|---|---|---|
| M2 | 骨架 + 调通 Python 内部接口 | ✅ |
| M3 | users 表 + BCrypt + JWT（`X-Demo-Roles` 已默认关闭） | ✅ |
| M4 | 角色 → ACL 投影（端到端验证：同一问题，hr 看得到、employee 看不到） | ✅ |
| M5 | 审计落库 + 查询（含 `decision=quota_denied`） | ⬜ |
| M6 | Vue 前端：登录 / 问答 / 文档 | ⬜ |
| M7 | 部署（Compose + Nginx + Tunnel） | ⬜ |

### 角色 → ACL（唯一映射点：`AclProjector`）

| 角色 | 拿到的 ACL | 能看什么 |
|---|---|---|
| `employee` | `public` | 公开条款 |
| `hr` | `hr, public` | 公开 + 人事薪酬 |
| `admin` | `hr, finance, public` | 全部 |
| 未登记的角色 | **403**，不降级 | 无（显式失败，不静默给 public） |

投影后的 ACL 通过 `X-Auth-Roles` 头发给 Python —— 那是 v1 既有的头名（语义是"权限标记"，
和块里的 `chunk.acl` 同一套词表），改名会让 v1 的认证路径失效。

### 安全边界（现在成立的）

- 角色只有一个来源：**校验通过的 JWT**。请求体、自定义头都改不了它。
- 匿名访问 `/api/chat` 是 **401**，且在调用 AI **之前**就拒绝（不白烧生成）。
- 登录失败不区分"用户不存在"与"口令错"，并对不存在的用户也做一次哈希比对（时序对齐）。
- `INTERNAL_TOKEN` / `JWT_SECRET` 缺失或过短 ⇒ **启动失败**，不留到运行时。
