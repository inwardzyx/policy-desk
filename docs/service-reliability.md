# 服务间可靠性：熔断 + 隔离（实测记录）

> 2026-11-20 实测。目的是回答一个问题：**AI 服务（Python）挂掉时，Java 这边会怎样？**
> 结论：加护栏前会挂住/慢响应；加护栏后**8–11ms 快速失败**，且其他接口不受影响。

## 一、做了什么

| 能力 | 实现 | 参数 |
|---|---|---|
| 熔断 | Resilience4j `CircuitBreaker` | 窗口 4 次、失败率 ≥50%、打开后 30 秒半开 |
| 并发隔离 | Resilience4j `Bulkhead` | 最多 2 个并发调 AI，满了**立刻拒绝**（不排队） |
| 快速失败语义 | `PythonAiException(503)` → `503 SERVICE_UNAVAILABLE` | 与"上游返回错误 502"分开 |
| 可观测 | 拒绝时日志带 `[trace=xxxxxxxx]` | 便于在两个服务的日志里找同一次请求 |

**没有做的（如实）**：服务注册发现、配置中心、API 网关、跨服务 trace 落库。
理由：只有两个服务、一台机器，Compose 服务名即名字解析、Nginx 即网关、`.env` 即配置；
跨服务 trace 只做到了"调用方日志带 id"，Python 侧还没落同一 id。

## 二、故障注入实测

环境：Windows 本机，Java（Spring Boot 3.5 / JDK 25）8081，Python（FastAPI）8028。

| 场景 | 请求 | 状态码 | 耗时 |
|---|---|---|---|
| **正常** | `POST /api/chat` | **200** | 530 ms |
| **Python 进程停掉** | chat #1 | **504** | 27 ms |
| 同上 | chat #2 | 504 | 8 ms |
| 同上 | chat #3 | 504 | 10 ms |
| 同上 | chat #4（熔断打开） | **503** | 11 ms |
| 同上 | chat #5 | 503 | 10 ms |
| 同上 | chat #6 | 503 | 10 ms |
| 同上 | **`GET /actuator/health`（隔离检验）** | 503 | **48 ms** |
| **Python 重启 + 30 秒后** | chat #1（半开试探） | **200** | 4113 ms |
| 同上 | chat #2 | 200 | 496 ms |
| 同上 | chat #3 | 200 | 966 ms |

## 三、怎么读这些数

- **前 3 次 504 是"连接失败"**（Python 没进程，连接被拒 → `ResourceAccessException` → 504）；
- **第 4 次开始 503**：熔断器在 4 次失败后打开，之后**不再尝试打上游**，直接快速失败；
- **10 ms vs 530 ms**：正常路径要等模型，故障路径立刻返回 —— 这就是护栏的意义；
- **`/actuator/health` 48 ms**：熔断打开时，**别的接口没有被拖慢**（隔离生效）；
  ⚠️ **2026-11-21 更正**：这条只在"健康检查不走熔断器"时成立。真实拓扑里
  `PythonAiHealthIndicator` 一开始是**直接打上游**的，AI 停掉时每次健康检查要等 **~2.2 秒**
  才回 DOWN（见第五节之后的"七、整栈 smoke"）。已改成走熔断器，现在毫秒级返回 DOWN。
- **恢复后第一次 4113 ms**：半开状态放一个请求去试探，成功后才关闭熔断，之后回到正常延迟。

## 四、判据（可复跑）

```powershell
# 1) 停掉 Python
Get-NetTCPConnection -LocalPort 8028 -State Listen | ForEach-Object { Stop-Process -Id $_.OwningProcess -Force }
# 2) 连打 6 次 /api/chat：应看到 504 ×3 → 503 ×3，且后三次 < 20ms
# 3) 重启 Python，等 30 秒后再打：应恢复 200
```

单元测试（不需要网络）：

```powershell
D:\tools\apache-maven-3.9.16\bin\mvn.cmd -B -ntp -f apps\api\pom.xml test
# AiGuardTest 4 条：熔断打开后不再打上游、隔离满员立刻拒绝、被拒绝的不计入熔断失败率
```

## 五、一个取舍（面试会被问）

> "为什么不上 Nacos / Gateway / Config Server？"

```text
两个服务、一台机器：注册发现解决的问题（动态地址）不存在——Compose 服务名就是 DNS；
网关解决的问题（统一入口/限流/路由）Nginx 已经做了；
配置中心解决的问题（多环境/动态刷新）现在只有 5 个环境变量。
真正会咬人的是"上游挂了/慢了怎么办"，所以我只补熔断、隔离、快速失败，
并用故障注入证明它确实生效。
```

---

## 六、入口层限速（2026-11-21 补）

### 6.1 为什么加这一层

算法仓库实测（`campus-policy-rag` 的 `experiments/noisy-neighbor.md` §九/§十）：
**每秒上千次「拒绝」本身就能把应用进程的事件循环占满** —— 那时候连不碰检索的 `/health`
都慢了 30 倍（3.3ms → 102.1ms），而 CPU 只到 57%。

把"拒绝"放到**入口**（nginx，C 进程、自己的事件循环）之后，同样的风暴只让入口忙：
同一台应用、同款客户端，邻居的 p95 劣化从 **+623% → +179%**。

### 6.2 加了什么（`deploy/nginx.conf`）

```nginx
limit_req_zone  $binary_remote_addr zone=api_rate:10m rate=10r/s;
limit_conn_zone $binary_remote_addr zone=api_conn:10m;
# 在 location /api/ 里：
limit_req  zone=api_rate burst=20 nodelay;  limit_req_status  429;
limit_conn api_conn 12;                     limit_conn_status 429;
add_header Retry-After 2 always;
```

- **键 = `$binary_remote_addr`**：nginx 手上只有这个不会撒谎的标识。
  `X-Client-Id` 这类自称头谁都能伪造（Python 侧 `limits.py` 默认就不信它们）。
  ⚠️ **代价**：办公室共用一个出口 IP 时几个人共享同一个桶 —— 所以阈值给得宽。
  等有可信租户标识（Java 下发 / 网关注入）再换键，其它一行不动。
- **每 IP 12 条连接**：不能设小 —— SSE 流式回答会一直占着连接（一次生成 5–30 秒），
  浏览器同域还会并发开几条，设 8 会把正常用户挡掉。
- **`Retry-After`**：不带这个头，客户端会立刻重打 —— 那正是拒绝风暴的来源。

### 6.3 怎么验（含一个会假绿的写法）

规则是：**判据要先证明它会红**。下面的最小 conf 与本项目 `deploy/nginx.conf` 的
zone/limit 行完全一致，上游换成本机一个静态文件服务（不用起 Java/Python）：

```powershell
# 上游（随便一个静态服务即可）
D:\Python-project\.venv\Scripts\python.exe -m http.server 8064 --bind 127.0.0.1
# 入口（把上面那段 conf 挂进 conf.d）
docker run -d --name edge-limit-check -p 8063:80 --add-host=host.docker.internal:host-gateway `
  -v <你的最小 conf>:/etc/nginx/conf.d/default.conf:ro nginx:latest
```

实测（2026-11-21）：低速 12 次（约 3 r/s）全部放行；**突发 60 次 → 21 过、39 个 429，带 `Retry-After: 2`**
（21 = 1 + `burst=20`，与配置相符）。

> ★ **别用 `return 200` 当"上游"**：`return` 在 **rewrite 阶段**就短路了，而 `limit_req`
> 在 **preaccess 阶段**才跑 ⇒ 60 次突发**全部 200**，看起来"限速没生效"。
> 我第一次就是这么验的，差点把"配置错了"记成"nginx 不生效"。换成真 `proxy_pass` 才见到 429。
> 这是本项目"扫到 0 条要报红"那条纪律的另一个面：**没触发 ≠ 没问题，可能是判据根本没跑到。**

### 6.4 部署形态下到底谁在限并发（别记错）

```text
宿主 → nginx(/api/ 每 IP 限速) → Java(Bulkhead 2 + 熔断) → Python ai:8000（不对外）
```

- `/search` 在部署形态下**没有对外入口**（Java 只打 `/internal/chat` 与 `/agent`），
  所以 Python 侧的 `/search` 并发准入是**兜底**（给"直连 Python"的 dev/MCP 形态用）；
- 真正在生产里起作用的并发上限是 **Java 的 Bulkhead(2)** + 这一层入口限速；
- `deploy/docker-compose.yml` 现在也把 Python 侧那几个旋钮设上了：
  `MAX_CONC_PER_CALLER=2`、`GLOBAL_CONC=2`、`CONC_WAIT_MS=250`、`EMBED_THREADS=4`。
  **它们之间的关系**：`GLOBAL_CONC × EMBED_THREADS ≲ 机器核数`（容器里 `os.cpu_count()`
  看到的是宿主机核数，不能靠"容器只有 2 核"来兜），改之前先算。

---

## 七、整栈 smoke（2026-11-21）：在真实拓扑里再验一遍

前面的 §二 是在**开发形态**（Python 8028 跑在宿主）里做的故障注入。这一节是
`docker compose up` 起五容器（web/api/ai/mysql/redis）之后的**同一批判据**，
全部经 `nginx(8088) → Java(api) → Python(ai)`。

### 7.1 结果

| 检查 | 结果 |
|---|---|
| `docker compose ps` | web / api / ai / mysql / redis **全部 healthy** |
| `GET /` | 200 |
| `POST /api/auth/login`（hr_demo） | 200，62ms |
| `POST /api/chat`（命中文档） | **200 / 3065ms**，`knowledge_hit=true`，1 条引用 |
| `POST /api/chat`（库里没有） | 200 / 1869ms，`knowledge_hit=false`，明确拒答 |
| **入口限速**（突发 60 次） | **24 × 401（放了，Java 判未登录）+ 36 × 429**，429 带 `Retry-After: 2` |
| **熔断**（停 ai 后连打 6 次） | **504、504**（307ms / 20ms，真连接失败）→ **503 × 4**（12–36ms，熔断打开） |
| **恢复**（重启 ai） | 第 1 次 2462ms（半开试探）→ 之后 648–1038ms，全 200 |
| **健康检查**（熔断期，容器内直连 8081） | **177ms** 返回 `{"status":"DOWN", ..., "pythonAi":...}`（≈150ms 是 `docker exec` 开销） |

### 7.2 两个真坑（都差点让我写错结论）

**① 镜像里的 jar 是旧的，第一次 smoke 的 6 次 504 是"旧代码"的行为。**
`policy-desk-api:local` 镜像构建于 **11-05**，而熔断是 **11-20** 才提交的 ——
所以第一次"停 ai 连打 6 次"全是 504（旧代码只有超时映射，根本没有熔断器）。
我一度准备写"熔断在 compose 里没生效"。核对了三处才发现是产物过期：

```powershell
docker image inspect policy-desk-api:local --format '{{.Created}}'   # 2026-11-05
git log -1 --format='%ci' -- apps/api/src/main/java/com/policydesk/api/client/AiGuard.java  # 2026-11-20
```

⇒ **smoke 之前先确认"跑的是不是 HEAD 的产物"**：`mvn package` → `docker build` → `up -d --force-recreate`。
和本项目"数字要能指回产物"是同一条纪律，只不过这次指的产物是**镜像**。
（顺带：`target/*.jar` 的 mtime 在 repackage 后可能不变，别拿它判断新旧 —— 看 size 或直接解包看有没有新类。）

**② `/actuator/health` 原来绕过熔断器，AI 挂了它要等 ~2.2 秒。**
实测（同一方法、同一个故障）：修前 **2191 / 2192 / 2179 ms**，修后 **177 ms**（≈exec 开销）。
后果是"上游挂了"被放大成"Java 的监控也卡住"，正是本项目记过的**"防护把自己变成故障"**。
修法：`PythonAiHealthIndicator` 改走 `AiGuard.breaker()`（**只走熔断器，不走 Bulkhead** ——
健康检查不该和用户请求抢并发，否则一拥堵就会出现"AI 好好的、健康检查却 DOWN"的假红）。
`mvn test` 67 条全绿。

### 7.3 演示账号配额（smoke 会打光）

配额按人算，演示账号各 **12 次/天**（`quota:<userId>:<date>`，Redis）。打光了会得到
`429 今日配额已用尽（13/12）`，**不是熔断**（这两个 429 长得像，别混）：

```powershell
docker compose --env-file .env exec -T redis redis-cli DEL quota:1:2026-11-21 quota:2:2026-11-21
```


