# 制度问答台 · policy-desk

> 一个**能证明自己没变坏**的企业制度问答系统。
> 每条答案带可核验出处，答不出就明确拒答，改动发布前必须过评测门禁。

> 📄 **接手/上手请看 [HANDOFF.md](HANDOFF.md)** —— 现状、待办、环境事实、以及 20 多条真实踩过的坑。

**当前进度：M7 完成** —— `docker compose up -d` 起全套（web / api / ai / mysql / redis），公网经 Cloudflare Tunnel 可达。

[![ci](https://github.com/inwardzyx/policy-desk/actions/workflows/ci.yml/badge.svg)](https://github.com/inwardzyx/policy-desk/actions/workflows/ci.yml)
&nbsp;
[![Java](https://img.shields.io/badge/Java-17-007396?logo=openjdk&logoColor=white)](https://openjdk.org/projects/jdk/17/)
[![Spring Boot](https://img.shields.io/badge/Spring_Boot-3.5-6DB33F?logo=springboot&logoColor=white)](https://spring.io/projects/spring-boot)
[![Vue](https://img.shields.io/badge/Vue-3-4FC08D?logo=vuedotjs&logoColor=white)](https://vuejs.org/)
[![Python](https://img.shields.io/badge/Python-FastAPI-3776AB?logo=python&logoColor=white)](https://fastapi.tiangolo.com/)

> 算法层在另一个仓库：[campus-policy-rag](https://github.com/inwardzyx/campus-policy-rag)（检索 / 精排 / 拒答 / 评测门禁）。
> 两层各自可独立测试与替换 —— 业务层不认识"向量"，算法层不认识"用户"。

| 检查 | 覆盖什么 |
|---|---|
| `java` | `mvn test`（63 条，H2 内存库，不连 MySQL） |
| `web` | `npm ci` → `vue-tsc --noEmit` + `vite build` → `vitest`（4 条） |
| `deploy` | `docker compose config -q` + `nginx -t` + `.ps1` 编码（静态校验，不起容器） |

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

## 怎么跑（四个进程）

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

# 3) 前端（开发热更新）
cd D:\policy-desk\apps\web
npm install          # 首次
npm run dev          # http://localhost:5173

# 3') 或跑构建产物
npm run build
npx vite preview --port 5174     # http://localhost:5174
```

★ **端口 5173 可能被别的项目占着**（本机就有一个 `D:\agent-eval-sandbox\web` 的 vite）。
`--strictPort` 会直接报错而不是悄悄换端口；换一个端口跑就行（代理配置跟着端口走，不用改）。

★ `vite preview` 只监听 `::1`（IPv6 localhost），所以用 `http://localhost:5174` 访问；
用 `127.0.0.1:5174` 会"拒绝连接"——这不是服务没起来。

★ 开发期不配 CORS：前端用 Vite 代理把 `/api` 转到 8081，生产用 nginx 同源反代。
**不给后端留一个只在开发时才需要的跨域口子。**

构建（本机没装 Maven，仓库自带一份在 `tools/` 下）：

```powershell
$env:JAVA_HOME="D:\idea"
D:\policy-desk\tools\apache-maven-3.9.9\bin\mvn.cmd -s D:\policy-desk\tools\settings.xml -f apps\api\pom.xml test
```

---

## 验证（2026-11-05 实测，非估算）

### Java 侧（H2 内存库，57 条）

```text
Tests run: 57, Failures: 0, Errors: 0, Skipped: 0
```

| 判据 | 证明什么 |
|---|---|
| `AclProjectorTest` 6 条 | 三个角色的映射；多角色取并集；**输出顺序固定**；未知角色/空角色**抛错而不是降级** |
| `QuotaServiceTest` 3 条 | 限额内放行、超出拒；**被拒那次也计数**；**键里带日期**（跨天自动换计数器） |
| `RedisQuotaStoreTest` 3 条 | **TTL 只在首次 INCR 时设置**（否则永不过期）；Redis 返回 null 不炸 |
| `AuditServiceTest` 3 条 | 问题只存 64 位 sha256；同问题同哈希；**写库失败被吞掉**（旁路证据） |
| `ChatServiceTest` 5 条 | answered/refused 分开记；**配额拒时不调 AI**且记 `quota_denied`；上游错记 `upstream_error` 后继续抛；无 trace 记 null 不补假值 |
| `JwtServiceTest` 5 条 | 令牌签得出/验得回；**过期拒**；**改一个字符拒**；**换密钥拒**；密钥 <32 字节**构造即失败** |
| `JwtAuthFilterTest` 4 条 | 无令牌放行但不设身份；**坏令牌 401 且不再往下走**；好令牌写入身份；`/actuator/**` 不过滤 |
| `AuthServiceTest` 4 条 | 口令对给令牌；**口令错／用户不存在／被禁用返回同一个错误** |
| `ChatControllerTest` 10 条 | **传给业务层的是 ACL 不是角色名**；未知角色 403 **且不调用**；匿名 401 **且不调用**；已认证时 `X-Demo-Roles` 无效；配额 429+`Retry-After`；502/504 分开 |
| `AuthControllerTest` 3 条 | 登录成功给 token；失败不可区分；空输入 400 |
| `LoginIntegrationTest` 4 条 | 种子账号→登录→`/api/me` 全链路；**健康检查在上游不可用时真的报红** |
| `PythonAiClientTest` 3 条 | 两个头发对；上游 401 变可判断异常；上游多字段不炸解析 |
| `PythonAiPropertiesTest` 3 条 | 缺 token/base-url **构造即失败**；超时非正数退回默认 |

### 配额 + 审计（M5 端到端，真 MySQL，2026-11-05）

`QUOTA_DAILY_LIMIT=2`，用 `hr_demo` 连问三次：

| 次数 | 结果 |
|---|---|
| 1 | 200，`knowledge_hit=true`，1 条引用 |
| 2 | 200 |
| **3** | **429** `今日配额已用尽（3/2）`，`Retry-After: 51557`（距次日 0 点秒数） |

`audit` 表（真 MySQL）：

```text
id  user_id  hash10       decision       n_blocks  took_ms  trace_id
1   1        84b03d71d1   answered       1         4029     NULL
2   1        84b03d71d1   answered       1         1004     NULL
3   1        84b03d71d1   quota_denied   NULL      NULL     NULL
4   1        868e97d9c0   quota_denied   NULL      NULL     NULL
```

- 前两条是同一个问题（哈希相同）→ **能看出"同一问题问了两次"**
- 后两条 `quota_denied` 也留了痕 → **被刷时看得见**（这正是 M5 要证明的那件事）
- 带 `namespace` 的请求会把 Python 的 `trace_id` 落库，两边能对上：
  `context.trace_id = deca72a3acf24c25b5f262792e0f1e5c` ↔ `audit.trace_id = deca72a3acf24c25b5f262792e0f1e5c`

### 前端（M6a，2026-11-05 实测）

```text
npm run build   → 32 modules，dist JS 98.79 kB（gzip 38.74 kB）
npm run test    → 3 passed（登录存令牌 / 401 清令牌 / ProblemDetail 透出）
```

经 `vite preview`（5174）的代理打到真实后端：

| 检查 | 结果 |
|---|---|
| `GET /` | 200，HTML 含 `<div id="app">` 与打包后的 JS/CSS |
| `GET /assets/index-*.js` | 200，98,794 bytes |
| `POST /api/auth/login`（经代理） | 200，拿到 JWT，`roles=["hr"]` |
| `POST /api/chat`（经代理，带令牌） | 200，`knowledge_hit=true`，1 条引用 `学生纪律处分管理规定.md｜第四章-第二十六条` |
| `POST /api/chat`（匿名） | **401** —— 前端据此清令牌并跳登录页 |

★ 前端**不引 UI 组件库**：两个页面用不到，引一个要多 200KB 且要处理按需引入。
等文档页上来再评估。

### 文档页（M6b，2026-11-05 实测）

权限分两档：**看**任何登录用户；**写**（上传/删除）只有 `hr` / `admin`。
★ 内部口令是**服务身份**，它证明"请求来自 Java"，不证明"这个人是 hr"——两者混用，
任何登录用户都能写库。

| 步骤 | 结果 |
|---|---|
| hr 列 `company` | 1 篇：`ac27eba2b698 企业规章制度（50条） acl=public` |
| hr 上传（multipart，`acl=hr`，到 `m6btest`） | 200，`doc_id=a7338679b66c`，该分区 1 块 |
| 再列 `m6btest` | 出现该文档，`acl=hr`、`tombstone=false` |
| **employee 上传** | **403** `只有 hr / admin 能上传或删除文档` |
| 未登录访问 `/api/docs` | **401** `未认证` |
| hr 删除 | `deleted=true` |
| 删除后再列 | 0 篇 |

★ Java 侧的是 multipart，Python 侧吃的是 **raw body + query 参数**，转换发生在
`DocumentsClient.upload()`：前端用 multipart 是 `<input type=file>` 的自然形式，
不该为了迁就后端把它改成手写字节流；也不该在 Python 侧再实现一套 multipart 解析。

### 部署验证（M7，2026-11-05 实测）

| 检查 | 结果 |
|---|---|
| `docker compose ps` | web / api / ai / mysql / redis **全部 healthy** |
| `GET /`（经 nginx，8088） | 200 |
| `POST /api/auth/login` | 200，`roles=["hr"]` |
| `POST /api/chat`（经 nginx → java → ai，真打 DeepSeek） | **4011ms**，`knowledge_hit=true`，1 条引用 |
| 上传（multipart，经 nginx 20m 限制） | 200，`doc_id=ee14f76815f6`，1 块 |
| 列表 / 删除 | 1 篇 → `deleted=true` |
| **公网**（`*.trycloudflare.com`） | 首页 200、登录 200、问答 **4438ms** 命中 1 条 |

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
| M5 | 配额（每日 N 次，可换 Redis）+ 审计落库/查询 | ✅ |
| M6a | Vue3 前端：登录 + 问答（Vite 代理直连 Java） | ✅ |
| M6b | 文档页：列表 / 上传 / 删除（写操作只给 hr、admin） | ✅ |
| M7 | 部署：compose 五容器 + nginx 同源反代 + Cloudflare Tunnel | ✅ |

---

## 部署（本机 + Tunnel）

**要求：两个仓库并排克隆** —— `policy-desk`（业务 + 前端）与 `_review_rag-cs`（算法）。
compose 里 ai 服务的 build context 是 `../../_review_rag-cs`。这是"业务仓库 + 算法仓库"的常态，
但只拿到一个 repo 的人会懵，所以写在这里。

```powershell
cd D:\policy-desk\deploy
copy .env.example .env        # 然后填 5 个值（见下）
mvn -f ../apps/api/pom.xml package -DskipTests    # 镜像里 COPY jar，所以先构建
docker compose up -d --build
docker compose ps             # 五个服务都 healthy
# 浏览器：http://localhost:8088
```

`.env` 里必须有：`DB_PASSWORD`、`MYSQL_ROOT_PASSWORD`、`INTERNAL_TOKEN`、
`JWT_SECRET`（>=32 字节）、`DEEPSEEK_API_KEY`（生成用，缺了问答会 500 —— 刻意的）。

### 拓扑

```text
公网 ──Cloudflare Tunnel──▶ web(nginx:80, 宿主 8088)
                              ├── /            → 静态文件（dist）
                              └── /api/*       → api:8081 ──▶ ai:8000
                                                                └──▶ DeepSeek（外部）
                              mysql / redis 只在内部网络，不映射端口
```

★ **只暴露 8088 一个端口**。把带数据库的机器暴露到公网，是这类项目最不可原谅的事故。

### 三个已踩过的坑（都写进配置了）

1. **8080 被 WSL 的 `wslrelay` 占着** → compose 直接报 `port is already allocated`。
   默认端口改成 **8088**，并用 `${WEB_PORT:-8088}` 允许覆盖。
2. **nginx 默认 `client_max_body_size 1m`** → 上传制度 PDF 会 413，且错误页是 nginx 的 HTML，
   前端拿不到 `detail`。已和 Java 的 `multipart.max-file-size: 20MB` 对齐。
3. **`init.sql` 只在数据卷为空时执行**（`/docker-entrypoint-initdb.d` 的语义）。
   改了 DDL 不生效时，要么手动迁移，要么 `docker compose down -v` 重来 ——
   后者会清空业务数据，先想清楚。

### 公网链接的定位（重要）

```text
Cloudflare quick tunnel 给的 URL 每次重启都变，也没有 uptime 保证。
⇒ 它适合"面试时现场演示"，不适合当简历上那条链接。
⇒ 想固定：买一个域名（约 ¥50/年）+ cloudflared named tunnel，仍然跑这台机器。
   不想让机器常开，才需要 VPS（约 ¥40/月）。
★ 不会失效的"链接"是：仓库 + 一条 docker compose up + 一段 3 分钟录屏。
录像怎么拍（逐字旁白、三句已实测的演示问题、翻车预案）见 [docs/demo-script.md](docs/demo-script.md)，
语料准备用 `scripts/demo-seed.ps1`。
```

### 配额与审计（M5）

**配额**：每用户每天 `QUOTA_DAILY_LIMIT` 次（默认 12），键 `quota:{userId}:{yyyy-MM-dd}`。

| 存储后端 | 何时用 | 代价 |
|---|---|---|
| `memory`（默认） | 测试、单机演示 | 重启清零、多实例各算各的 |
| `redis`（`QUOTA_BACKEND=redis`） | 部署形态 | 需要真 Redis；TTL 跨天自动过期 |

★ **先扣再判**：被拒的那次也占用计数，否则用户可以在超额后无限重试、每次都是"第 N+1 次"。
★ 配额在 Java 侧判定，**超额请求根本不会打到 Python**，也就不烧 token。

**审计**：每次问答落一行 `audit`，`decision` 取 `answered / refused / quota_denied / upstream_error`。
问题**只存 sha256**（能比对频次，还原不出原文），不存答案正文，`trace_id` 与 Python 侧对齐。

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
