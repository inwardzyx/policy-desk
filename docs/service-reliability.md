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
