# 项目提案：分布式评测调度与质量门禁平台

> 状态：**待评审**（2026-11-25）。这份是提案，不是已完成的项目。
> 所有性能/容量数字标注为「待量」——**没有量过的一律不写具体值**。
> 评审人请重点看第十一节列出的 5 个问题。

---

## 一、一句话

把"改 AI（prompt / 模型 / 检索参数）"从**改代码 + 重启 + 祈祷**，变成一次**可门禁、可灰度、可回滚、可审计**的发布。

核心不是"AI"，是后端：调度器、幂等、分布式锁、状态机、消息、超时预算。

---

## 二、目标与非目标

### 2.1 目标（按优先级）

1. **补后端深度**：让简历上出现第二条后端项目，考点与现有 `policy-desk` **互补而不重复**。
2. **能被追问**：每个技术决定都有"为什么"，每个判据都能现场演示。
3. **和 AI 方向对齐**：业务场景是 AI 变更的评测门禁，不是电商。

### 2.2 非目标（明确不做）

| 不做 | 理由 |
|---|---|
| 前端 UI | 一页 HTML + curl 够用；时间应花在后端 |
| 注册中心 / 配置中心 / API 网关 | 一台机器、4 个进程。Compose 服务名即 DNS，nginx 即网关；**而"版本化配置"本身就是这个项目的业务**，Nacos 只做下发、不做质量门禁 |
| 模型路由 / 计费 | 那是网关类项目，与本题无关 |
| 真实 LLM 评测成本控制 | 门禁用 mock 或小样本；真实评测复用现有 `evalset`，只跑必要的题 |

---

## 三、需求与立项依据

### 3.1 痛点

| 角色 | 问题 |
|---|---|
| 算法/开发 | 改了 prompt，本地看着好，**不知道线上 105 题会不会掉**；想先给 5% 用户试，没这个开关 |
| 值班 | 客服说"今天答得不对劲"，**不知道最近改了什么**，哪个提交是线上跑的那版 |
| 主管/合规 | "这次上线谁批的？影响哪些租户？出问题怎么退？"——答不上来 |

### 3.2 现在的替代方案为什么不够

`git revert` + 重启（约 2 分钟，待实测）+ 手工跑评测 + 人肉看报告。

**真正缺的不是回滚速度，是两件事**：① 没有"该不该上"的判据；② 没有"这次改动影响了谁"的清单。

### 3.3 真实样本（不是编的）

`campus-policy-rag` 的 4 份 `/chat` 基线，因为**判据版本 `fe0cf083` → `0a440c38`** 全部判 STALE，
简历引用的 42/44、40/61 当场失去可比性：改了什么、影响谁、能不能回滚，**当时一个都答不出来**。

### 3.4 什么情况下这个需求不存在（诚实边界）

只有 1 个模型、1 个 prompt、一周改一次、挂了直接重启 —— 那这就是过度设计。
**所以先量基线**：现在回滚一次多久？怎么知道改坏了？两个数决定后面重点做"门禁"还是"灰度"。

---

## 四、与现有项目的关系

| | `policy-desk`（已有） | 本项目（新） |
|---|---|---|
| 类型 | 业务后端 | 平台后端 |
| 考点 | 鉴权 / ACL / 配额 / 审计 / 熔断 / 入口限速 | **调度 / 分片 / 幂等 / 状态机 / 消息 / 分布式锁 / 超时预算** |
| 关系 | 可作为本平台的**被发布方**（把它的一次 prompt 替换当作发布） | 不重构它，只对接 |

⇒ 两个项目考点**互补**，不是"第二个 Java 项目"的重复。

---

## 五、架构

```text
                    ┌────────── nginx（入口，按 IP 限速）──────────┐
                    │                                              │
        ┌───────────▼────────────┐              ┌──────────────────▼─────────┐
        │ config-svc   :8081     │    REST      │ release-svc      :8082     │
        │ 配置版本 / 灰度规则     │◄─────────────│ 发布 / 回滚 / 审计          │
        │ 读多写少（走缓存）      │              │ 事务 + 乐观锁 + 状态机      │
        └────────────────────────┘              └───────────────┬────────────┘
                                                                │ 发布事件（至少一次）
                                                     ┌──────────▼──────────┐
                                                     │  Redis Streams      │
                                                     │ (group / ack /      │
                                                     │  pending / claim)   │
                                                     └──────────┬──────────┘
                                                                │ 消费（幂等）
                                                     ┌──────────▼──────────┐
                                                     │ gate-svc   :8083    │ 门禁调度
                                                     │ 分片 / 重试 / 死信    │
                                                     └──────────┬──────────┘
                                       提交任务(202+jobId)      │      回调结果
                                                     ┌──────────▼──────────┐
                                                     │ eval-executor       │ Python
                                                     │ 复用 evalset/105 题  │
                                                     └─────────────────────┘
```

### 5.1 每个拆分都要有理由（这是面试第一个问题）

| 服务 | 拆开的理由 |
|---|---|
| config-svc | **读高频**（每次请求都要读当前版本+灰度比例），要缓存；写极低频 |
| release-svc | **写高频低**但每次要事务 + 锁 + 审计；不能和读路径抢资源 |
| gate-svc | **长任务**（跑批几分钟），有独立线程池与失败域；它挂了不能把发布卡住 |
| eval-executor | Python 生态（评测资产在那），构成异构服务边界 |

> ★ 如果评审认为 config/release 拆开过度，**接受合并为一个服务**（那就退回 3 服务）。
> 我倾向拆开，因为"读路径要缓存、写路径要事务"这个理由能自证。

---

## 六、数据模型（草案，3 张表 + 1 张本地消息/事件表）

```sql
-- 1) 配置版本（config-svc 自己的库）
CREATE TABLE cfg_version (
  id           BIGINT PRIMARY KEY AUTO_INCREMENT,
  prompt_key   VARCHAR(64)  NOT NULL,
  version      INT          NOT NULL,
  payload      JSON         NOT NULL,          -- prompt 文本 / 模型名 / 检索参数
  gate_status  ENUM('PENDING','RUNNING','PASS','FAIL','SKIP') NOT NULL DEFAULT 'PENDING',
  created_by   VARCHAR(64)  NOT NULL,
  created_at   DATETIME(3)  NOT NULL,
  UNIQUE KEY uk_key_ver (prompt_key, version)   -- 幂等：同一版本只能建一次
) ENGINE=InnoDB;

-- 2) 发布记录（release-svc 自己的库）—— 状态机的载体
CREATE TABLE release (
  id            BIGINT PRIMARY KEY AUTO_INCREMENT,
  env           VARCHAR(16)  NOT NULL,          -- dev / prod
  prompt_key    VARCHAR(64)  NOT NULL,
  version       INT          NOT NULL,
  state         ENUM('PENDING','GATE_RUNNING','GATE_FAILED','CANARY','FULL','ROLLED_BACK')
                             NOT NULL DEFAULT 'PENDING',
  rollout_pct   TINYINT      NOT NULL DEFAULT 0, -- 0..100
  prev_release_id BIGINT     NULL,               -- 回滚目标（上一个 PASS 的 release）
  rev           INT          NOT NULL DEFAULT 0, -- ★ 乐观锁
  idempotency_key VARCHAR(80) NOT NULL,          -- ★ 客户端传，唯一
  operator      VARCHAR(64)  NOT NULL,
  created_at    DATETIME(3)  NOT NULL,
  updated_at    DATETIME(3)  NOT NULL,
  UNIQUE KEY uk_idem (idempotency_key)
) ENGINE=InnoDB;

-- 3) 门禁任务（gate-svc 自己的库）—— 分片与重试的载体
CREATE TABLE gate_job (
  id            BIGINT PRIMARY KEY AUTO_INCREMENT,
  release_id    BIGINT       NOT NULL,
  event_id      VARCHAR(64)  NOT NULL,
  shard_no      SMALLINT     NOT NULL,
  shard_total   SMALLINT     NOT NULL,
  state         ENUM('QUEUED','RUNNING','DONE','FAILED','DEAD') NOT NULL DEFAULT 'QUEUED',
  attempts      TINYINT      NOT NULL DEFAULT 0,
  next_run_at   DATETIME(3)  NOT NULL,           -- 退避重试
  result_json   JSON         NULL,
  UNIQUE KEY uk_event (event_id)                 -- ★ 幂等：同一事件只建一个任务
) ENGINE=InnoDB;

-- 4) 事件表（gate-svc 的本地消息表，用于"发布事件"的可靠投递）
CREATE TABLE outbox_event (
  id          BIGINT PRIMARY KEY AUTO_INCREMENT,
  event_id    VARCHAR(64) NOT NULL,
  type        VARCHAR(32) NOT NULL,              -- RELEASE_CREATED / GATE_DONE ...
  payload     JSON        NOT NULL,
  state       ENUM('NEW','SENT','CONFIRMED') NOT NULL DEFAULT 'NEW',
  created_at  DATETIME(3) NOT NULL,
  UNIQUE KEY uk_event (event_id)
) ENGINE=InnoDB;
```

> **每个服务自己的库/表，不共享库**（微服务的第一条纪律，也是面试必问）。
> 为了省事可以共用一个 MySQL 实例，但**用不同 schema + 不同账号**，并在文档里写明"物理共库、逻辑分库"。

---

## 七、关键流程

### 7.1 发布状态机

```text
            ┌──────────┐  提交门禁   ┌──────────────┐  通过   ┌────────┐  放量  ┌──────┐
  创建版本 → │ PENDING  │───────────▶│ GATE_RUNNING │────────▶│ CANARY │───────▶│ FULL │
            └────┬─────┘            └──────┬───────┘         └───┬────┘        └──────┘
                 │ 校验失败                │ 门禁不过             │ 人工/自动回滚
                 ▼                         ▼                     ▼
             (拒绝，不落库)          ┌──────────────┐      ┌─────────────┐
                                    │ GATE_FAILED  │      │ ROLLED_BACK │
                                    └──────────────┘      └─────────────┘
```

**回滚 = 把"当前版本指针"切回 `prev_release_id`，不是重建**。这就是"10 秒回滚"的原理
（一次原子 UPDATE + 两条审计），要讲清楚它覆盖什么、不覆盖什么（见 §11 的问题 4）。

### 7.2 灰度分流的稳定性（关键）

```text
bucket = murmur3(tenantId) % 100
命中的版本 = bucket < rollout_pct ? 新版本 : 旧版本
```

- 同一租户**永远落同一侧**（不能随机，否则用户会看到"A 一句 B 一句"）；
- 版本切换瞬间的一致性靠"**版本指针单行原子读**"（不是"读配置再读版本"两步）；
- 放量到 100% 后，才允许把旧版本标记为可回收。

### 7.3 长任务不能挂在 HTTP 上

```text
gate-svc  ──POST /eval/submit（幂等键）──▶  eval-executor
          ◀── 202 + jobId ──
          （HTTP 立即返回，不阻塞）
eval-executor ──POST /gate/callback（jobId + 结果 + 签名/幂等键）──▶ gate-svc
```

理由：跑批几分钟，HTTP 挂住会占线程、超时、重试三件事全乱。
这是"超时预算"这道题的正确解法，也是面试可以展开的点。

---

## 八、技术选型与取舍

| 决定 | 选择 | 理由 / 放弃的方案 |
|---|---|---|
| 消息 | **Redis Streams** | 已有 Redis；有 consumer group / ack / pending / claim，语义够用；**不引 Kafka**：4 个进程一台机器，多一个部署单元是负收益。若 JD 点名 Kafka，用同一个 `EventBus` 接口加实现 |
| 可靠投递 | **本地消息表 + 扫描重发** | 不依赖 Streams 的"恰好一次"（它没有）；让"至少一次 + 消费幂等"成为显式设计 |
| 分布式锁 | Redis `SET NX PX` + 续期 | 锁只用来**减少冲突**；正确性靠 **DB 乐观锁 CAS**（双保险，锁失效也不会错） |
| 长任务通信 | 提交 + 回调 | 放弃"HTTP 同步等待"（占线程）和"轮询"（延迟高、DB 压力） |
| 服务发现 | Compose 服务名 | 放弃 Nacos/Eureka |
| 配置下发 | **本项目自己的 config-svc** | 放弃 Nacos Config：它只下发，不做"版本 + 门禁 + 灰度 + 回滚" |
| 观测 | 结构化日志 + 全链路 `X-Request-Id` | 放弃接入完整 APM（时间成本高），但 trace 必须跨 4 个进程能串起来 |

---

## 九、判据（每条都必须能"证明会红"）

| # | 判据 | 怎么验 | 变异用例（应该红） |
|---|---|---|---|
| 1 | 灰度稳定 | 同一 tenant 打 1000 次，**100% 落同一版本** | 把稳定哈希换成随机 ⇒ 判据必须失败 |
| 2 | 消费幂等 | 同一 `event_id` 投递 **100 次**，只产生 **1 个** gate_job | 去掉 `uk_event` 唯一索引 ⇒ 出现 100 个任务 |
| 3 | 发布原子性 | 在任意断点 `kill -9`，重启后状态 ∈ {PENDING/PASS/FAIL}，**不存在"版本已切、记录没写"** | 把"写版本"和"写记录"拆成两个事务 ⇒ 能造出半套状态 |
| 4 | 回滚 | 回滚 p99 **< 1s**（待实测），审计有"发布 + 回滚"两条 | 回滚走"重新发布旧版本"⇒ 耗时变成一次完整发布 |
| 5 | 失败域隔离 | executor 卡 10s，`GET /release/{id}` p95 **< 50ms**（待实测） | 把 gate 调用改成同步 ⇒ 查询接口跟着卡 |
| 6 | 不重不漏 | 500 任务 + 杀掉一个 worker ⇒ 每个任务**恰好成功一次** | 去掉 ack/claim ⇒ 出现重复或丢任务 |

> 纪律：**扫到 0 条要报红**。判据没触发 ≠ 通过，可能是判据没跑到（本项目踩过：
> 用 `return 200` 当上游，`limit_req` 在 rewrite 阶段就被短路，60 次突发全 200）。

---

## 十、里程碑（4 周；2 周缩水版见下）

| 周 | 交付 | 可验证产出 |
|---|---|---|
| W1 | config-svc + release-svc + 一条 REST 链路（建版本 → 发布 → 灰度读） | 同一 tenant 1000 次读同一版本（判据 1） |
| W2 | Redis Streams + outbox + gate-svc 消费 + 幂等 | 同一事件投 100 次只建 1 个任务（判据 2） |
| W3 | executor 对接（复用 evalset）+ 门禁判定 + 自动回滚 | 回滚 p99 + 判据 3、4 |
| W4 | 4 条判据的故障注入 + 压测 + 报告 + 简历条目 | 一份 `docs/` 报告，每个数能指回产物 |

**2 周缩水版**：只做 W1 + W2 的"调度器 / 幂等 / 锁 / 状态机"，砍掉灰度和自动回滚。
两个判据（2、6）就足够写进简历，而且都是纯后端。

---

## 十一、请评审人重点看的 5 个问题

1. **config-svc 与 release-svc 拆开是否过度？** 我的理由是"读路径要缓存、写路径要事务"。
   如果认为合成一个更合理，说理由（我不怕被否，怕的是拆了讲不出为什么）。
2. **长任务用"提交 + 回调"是否比"HTTP 同步 + 长超时"更好？** 回调要考虑：executor 崩溃时任务怎么办？
   我打算用"回调幂等键 + 超时扫描兜底"，这个兜底会不会变成第二套真相？
3. **幂等键选 `event_id` 够不够？** 是否需要 `(event_id, stage)`？—— 如果一个事件会触发多个阶段（提交/回调）。
4. **回滚用"指针切换"覆盖 90% 场景吗？** 什么情况下必须"重建"（比如新版本引入了数据迁移）？
   我打算在提案里明确"本平台不管 schema 迁移"，这个边界是否可接受。
5. **缩水成 2 周版（砍 gate 服务）后，还能讲成"微服务"吗？** 如果不能，最小的服务数是多少才站得住？

---

## 十二、风险与放弃条件（预先写死，避免死磕）

| 风险 | 触发条件 | 应对 |
|---|---|---|
| Java 不熟导致 W1 延期 | W1 结束还没一条跑通的链路 | **砍掉 gate 服务**，退回 3 进程版；门禁用同步调用 |
| Redis Streams 语义踩坑 | W2 结束 pending/claim 还没跑通 | 退化成本地消息表 + 轮询（同样是"最终一致性"，只是换个讲法） |
| 时间不够 4 周 | 第 3 周末 | 砍自动回滚，改"人工确认回滚 + 状态机"，并在文档里写清取舍 |
| 变成"又一个 CRUD" | 判据 1/2 都写不出来 | **项目作废**，改做"只补 policy-desk 的后端深度"（3–5 天） |

---

## 十三、面试可讲的三段

1. **设计取舍**：为什么 config 和 release 分开、为什么不用 Kafka、为什么回滚是指针切换。
2. **踩的坑**：至少一个"我的第一版错了"——例如灰度用了随机数、幂等键漏了阶段、锁和 CAS 重复了。
3. **数**：回滚 p99、重复投递下的幂等验证、杀 worker 的不重不漏。**每个数都要能指回产物**。

---

## 十四、待补的量（先量再改）

| 要量的 | 怎么量 | 用途 |
|---|---|---|
| 现在回滚一次多久 | `policy-desk` 改一个 prompt → 重启 → 验证 | 立项依据（基线） |
| 怎么知道"改坏了" | 有没有一个不看人肉的判据 | 决定重点做门禁还是灰度 |
| 单机 4 进程的内存/端口 | `docker stats` | 决定是否合并服务 |
