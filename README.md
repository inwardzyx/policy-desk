# 制度问答台 · policy-desk

> 一个**能证明自己没变坏**的企业制度问答系统。
> 每条答案带可核验出处，答不出就明确拒答，改动发布前必须过评测门禁。

**当前进度：M2 完成**（Java 业务层能用一个内部口令把问题转给 Python AI 服务并拿回答案）。

---

## 为什么是三层（而不是一个 Python 服务全干）

```text
浏览器  ──JWT──▶  Vue3（apps/web，W5 起）
                     │
                     ├──▶  Spring Boot（apps/api）  用户 / 角色 / 配额 / 审计 / 会话
                     │            │  HTTP + X-Internal-Token + X-Auth-Roles
                     │            ▼
                     └──▶  Python AI 服务（另一个仓库）  检索 / 精排 / 生成 / 评测
```

判断一段逻辑放哪边，只问一句：**它需不需要记住"上一次"？**
需要 ⇒ Java（有状态，要事务/持久化）；不需要 ⇒ Python（无状态，好重启、好扩容）。

部署形态上这还有一个直接后果：**配额用尽的请求在 Java 就被 429 掉，不会打到模型，也就不烧 token。**

---

## 怎么跑（M2，两个进程）

```powershell
# 1) Python AI 服务（另一个仓库，端口 8028）
$env:INTERNAL_TOKEN="dev-token"; $env:AUTH_MODE="proxy"; $env:TRUST_PROXY_HEADERS="1"
cd D:\_review_rag-cs
D:\Python-project\.venv\Scripts\python.exe -m uvicorn service:app --port 8028

# 2) Java 业务层（端口 8081）
cd D:\policy-desk\apps\api
$env:INTERNAL_TOKEN="dev-token"; $env:PYTHON_AI_URL="http://127.0.0.1:8028"
D:\idea\bin\java.exe -jar target\policy-desk-api-0.1.0-SNAPSHOT.jar
```

构建（本机没装 Maven，仓库自带一份在 `tools/` 下）：

```powershell
$env:JAVA_HOME="D:\idea"
D:\policy-desk\tools\apache-maven-3.9.9\bin\mvn.cmd -s D:\policy-desk\tools\settings.xml -f apps\api\pom.xml test
```

---

## M2 的验证（2026-11-04 实测，非估算）

| 检查 | 结果 |
|---|---|
| Java 单测 | **13 passed / 0 failed**（`PythonAiClientTest` 3、`PythonAiPropertiesTest` 3、`ChatControllerTest` 6、上下文加载 1） |
| `GET /actuator/health` | `components.pythonAi = UP`，带 `chunks=332`、`rejected=23`（真打了 Python 的 `/health`，不是只看自己活着） |
| `POST /api/chat`（问题「学生违纪处分有哪些等级？」） | **4886ms**（其中 Python 侧耗时 4784ms），`knowledge_hit=true`，引用 1 条：`学生纪律处分管理规定.md｜第四章-第二十六条` |
| 库外问题（「公司食堂几点开饭？」） | `knowledge_hit=false`、`sources=0`、答案「知识库里没有能回答这个问题的资料，我不编。」 |
| 空问题 | **400**（Bean Validation 拦住，不白烧一次生成） |
| 无口令直连 Python `/internal/chat` | **401** `缺少或错误的 X-Internal-Token` |

---

## 三条设计决定（都有判据守着）

1. **`INTERNAL_TOKEN` 缺失 ⇒ 启动即失败**，不是等到第一次请求。
   （`PythonAiProperties` 的构造器校验；判据见 `PythonAiPropertiesTest`）
2. **`/actuator/health` 必须带上游状态**。默健康检查只回答"我这个进程活着吗"，
   而真正让用户看到错误的分手是"Java 活着但 AI 挂了"——那时监控一片绿。
   （`PythonAiHealthIndicator`）
3. **上游错误分 502 / 504 两种**：502 = 它答了个错的；504 = 等不到。
   处置动作不同，合成一个 500 就是让人从日志里猜。（`ApiExceptionHandler`）

---

## 还没做（按里程碑）

| 里程碑 | 内容 | 状态 |
|---|---|---|
| M2 | 骨架 + 调通 Python 内部接口 | ✅ |
| M3 | 用户表 + JWT 登录（`X-Demo-Roles` 头届时必须关掉并删） | ⬜ |
| M4 | 角色 → ACL 投影（用 WireMock 断言投影内容，不断言 LLM 文本） | ⬜ |
| M5 | 审计落库 + 查询（含 `decision=quota_denied`） | ⬜ |
| M6 | Vue 前端：登录 / 问答 / 文档 | ⬜ |
| M7 | 部署（Compose + Nginx + Tunnel） | ⬜ |

★ M2 的 `X-Demo-Roles` 是**临时角色来源**，`policy-desk.demo-roles-header=false` 可关。
生产必须关，并在网关把这个头直接删掉——否则它是一条绕过登录的提权路径。
