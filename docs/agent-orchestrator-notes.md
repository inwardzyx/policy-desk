# 读 super-agent 编排层：8 条值得抄的做法

> 读于 2026-11-16，读的是 `D:\dsh-work\superagent-src`（DeepSeek 开源的 super-agent 原型）。
> 每一条都是「**它怎么做 → 代码在哪 → 对我有什么意义**」。
> 前 4 条我已经在自己项目里用上了（或踩过同款坑），后 4 条是以后要用的。

---

## 1. "谁能授权一次工作"必须是显式判据，不能靠"消息进来了"决定

**它怎么做**：每条消息先算 `human` 布尔值，再进授权矩阵。

```ts
// src/sources/github-trigger.ts:46
export const isHumanLogin = (user) => !/\[bot\]$/.test(user.login) && (!user.type || user.type === 'User')
// :62
const author = (user, agent) => ({ actor: { source: SOURCE, id: user.login },
                                   human: isHumanLogin(user) && !sameName(user.login, agent) })
```

⇒ 三类都不是"人"：`[bot]` 结尾的、`type` 不是 `User` 的、**以及 agent 自己**。

**意义**：我 11-16 实测过这条路——用 `github-actions[bot]` 在 Issue 里 @ 它，通知**进来了**
（`stats.received` 0→1），但决策是 `{"kind":"ignore","reason":[{"code":"not_human"}]}`。
**收到 ≠ 有权授权**。这跟我 `/agent` 里那条 gate 是同一件事：
原问题过不了 `/chat` 的闸，循环里查到什么都不许当成回答。

**我的项目里**：`agent/loop.py::gate`。区别是它把这条判断做成**数据**（`human: false` 写进 trigger），
我做成了**代码路径**（gate 回调）。它那样更好测——trigger 是纯数据，能直接喂单元测试。

---

## 2. 授权矩阵：四个格子，不是一个开关

```ts
// src/policy.ts:75-77
const Weak = z.enum(['inferred', 'ignore'])
AdmissionRowSchema = { listedUnaddressed?: Weak, unlistedAddressed?: 'explicit'|'inferred'|'ignore', unlistedUnaddressed?: Weak }
```

矩阵只有 **3 个格子**（`model.ts:98` 的 `AdmissionCell`）：

| 在不在 allow 名单 | 有没有 @ 到它 | 可配值 |
|---|---|---|
| 名单内 | 没 @ | `inferred` / `ignore` |
| 名单外 | @ 了 | `explicit` / `inferred` / `ignore` |
| 名单外 | 没 @ | `inferred` / `ignore` |

★ 注意**没有**"名单内 + @ 了"这一格 —— 它不可配，永远是 `explicit`：
你允许的人点名要它干活，就不该再被"降级成候选"。

**意义**：这是"放权"的正确粒度。我第一次做 agent 时的做法是二元开关
（`AGENT_ENABLED=1` 全开），而她这个项目将来要给更多人用，就会需要这张矩阵。

**我的项目里**：现在只有"开/关 + 原问题 gate"。等要放开给第二个人用，就该抄这张表。

---

## 3. LLM 的每一次判断都要有 schema + 交叉校验，缺一条就抛错

```ts
// src/policy.ts:40-46  assessmentOutcome()
if (judged.length !== enabled.length || ids.size !== judged.length || enabled.some(r => !ids.has(r.id)))
    throw new Error('Exactly one assessment for every enabled rule is required; ...')
```

**不是"漏评一条就算没命中"，是直接抛错。** 因为漏评 = 判据没跑，而没跑的判据不能算"没通过"。

后面还有三道：方向未定 → `directionUnsettled`；一条规则都没命中 → `noRuleMatched`；
`accept` 却没给 `intent_evidence` → throw。

**意义**：我在 `eval/` 里做过同一件事（数字对账脚本"扫到 0 条必须报红"），
但它用在 **LLM 结构化输出**上更狠：schema 本身成了判据（`.strict()` + `.refine()`），
模型少给一个字段就整条拒绝，而不是"当它没说"。

---

## 4. 接受工作的三个前提：方向已定 + 命中规则 + 有原文证据

```ts
// src/policy.ts:26-29  directionAllowed()
implementation_requested && target_basis !== 'undetermined' && !requires_design_decision
```

- `target_basis` 三档：`specified`（说清了改哪）/ `existing-standard`（有现成标准可对齐）/ `undetermined`（说不清）
- **说不清就不做**，而且 `requires_design_decision` 单独一栏（防止它替你发明设计）

`intent_evidence` 是必填的**原文引用**——"the quotes that establish the three facts"。

**意义**：这是"别自由发挥"的工程写法。我读到这里才意识到，
我的 `/agent` 里 `stopped_reason` 只是**结果**，而它连**为什么可以做**都要留证据。

**可迁移**：如果以后我的 agent 要改代码，得先要求它引用"哪一行、哪个需求"，
否则它就会把"看起来像要改"当成"要求改"。

---

## 5. 强度分级：`explicit` 直接干，`inferred` 只记录

```ts
// src/policy.ts:55-58
if (strength === 'inferred' && settings.eval.mode !== 'auto') {
  return { kind: 'ignore', audit: { ...audit, would_accept: true, proposal }, reason: { code: 'observed', ... } }
}
```

候选（bot 的、没 @ 的）在非 `auto` 模式下只写 `would_accept`，
**连"我本来打算做"都落库**，但不执行。

**意义**：这是渐进放权的标准做法——先观察它"会做什么"，再决定给不给它做。
比"先全开、出事再关"稳得多。

---

## 6. 决策必须可审计：reason code + 证据 + 候选卡片

`decision` 表一行长这样（我实测跑出来的）：

```json
{"kind":"ignore","reason":[{"code":"not_human"},
                           {"code":"said","values":{"text":"The sender is github-actions[bot], an automated account whose messages cannot authorize work. Its content ... also matches neither enabled rule ..."}}],
 "policyVersion":"b7f3481d68b408ee845c4f539bac0a04","strength":"inferred","at":"..."}
```

配套 `disposition` 表还记了 `fanout: []`（没人接）和 `evalId`。

**意义**：这就是为什么我能**在 10 分钟内定位"为什么没工单"**——
不是翻日志猜，是查表。我的评测体系里对应的是 `报告头 + stopped_reason`；
它比我多一层：**`policyVersion`**（这条判断是按哪版规则做的）。

**这条我要抄**：报告头该记"判据版本"，不只是代码版本。

---

## 7. 事件去重靠"带版本的 ID"，不靠时间窗口

```ts
// src/sources/github-trigger.ts:68
const id = key('github-comment', comment.html_url ?? `github:${ref.number}`, String(comment.id), version)
// intake.ts:23
if (this.repo.triggers.get(trigger.id)) return 'duplicate'
```

`version` 是 `updated_at`：**编辑评论 = 新事件**，重复轮询 = 同一 ID = 丢弃。

**意义**：我 repo 里的 `runstate` 指纹做的是同一类事（内容变了指纹就变）。
但事件层我暂时没有——`/agent/stream` 现在没有去重也没有重放保护。

---

## 8. 输入通道是单点：`state=ready` 但 `received=0` 是完全正常的表象

`Intake.receive()` 的三条出口都**落库**：

```ts
// intake.ts:23,26-29,53-55
'duplicate'  → 不写（靠 ID 判）
'filtered'   → disposition { filtered: { code: reason } }
'stored'     → disposition { fanout, context }
```

所以"没工单"能区分成三种：**没收到 / 收到了被过滤 / 收到了没人接**。

**意义**：这正是我 11-12 踩过的坑的另一种形态——
两份报告都 FRESH 但环境不同（"看起来健康，其实什么都没发生"）。
**可观测性不是"有指标"，是"每个事件都有归宿"。**

---

## 一句话

> 它最值得抄的不是 agent 循环（那个我自己写过，还做过三种实现对照），
> 是**编排层的判据**：谁能授权、什么时候只观察、拒绝了要留什么理由。

如果要挑两条马上用：

1. **报告头记"判据版本"**（§6）——半小时的活，直接加强我那套新鲜度检查。
2. **事件落库时记 disposition**（§8）——`/agent` 现在只有 `steps`，没有"这次为什么没做成"的独立记录。
