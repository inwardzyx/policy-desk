# HANDOFF —— 交接文档

> 写于 2026-11-11。给"完全没参与过这个项目的人（或新的 AI 会话）"看。
> 读完之后你应该能：**知道这是什么、现在到哪一步、下一步做什么、以及别踩哪些坑。**

---

## 0. 30 秒速览

```text
这是什么    一个叫「制度问答台」的项目：企业/学校制度文档的问答系统，答案带原文出处、答不出就拒答。
             三层架构：Vue3 前端 → Spring Boot 业务层 → Python 算法层。
它现在在哪  代码全做完（M2–M7）、两个仓库公开且 CI 全绿、简历三版已出、录像脚本已写、
             评测基线已出（promptfoo，held-out 105 题，离线/在线两份分数）。
             卡在「投递」：用户已投 200 份中小厂实习，0 面试。
下一步该做  ① 找人标 20 条（κ 的样本，脚本与规范已就位）② 改投递策略并恢复投递
             （promptfoo 基线 + 配对门禁 + κ 脚本 + DECISIONS.md 均已完成，见 §5）
```

## 1. 用户是谁（决定"什么算有用"）

- 韩山师范学院，软件工程 **五年制专本衔接**，2025 级、**2030 年毕业**（已在读，不是 2026 届）。
- **可立即到岗**，每周 4–5 天，可连续 6 个月。求职方向：**AI 应用开发 / 后端开发 / 评测（实习）**。
- 技术底子：Python 较熟；Java/Spring Boot/MySQL/Redis 学过但**本项目是第一个项目**；前端零基础起步（第一次写 Vue）。
- **硬事实：投过 200 份中小厂实习，0 面试。** 所以"再加一个项目"的边际收益很低，卡点在被筛掉。

> ⚠️ 和用户沟通时注意：她**不是**"想再学点东西"，她要的是**被录用**。任何"再做三个月的项目"的建议都会被正确地质疑。

## 2. 两个仓库的关系

```text
D:\policy-desk          ← 业务层 + 前端（32 个提交）   github.com/inwardzyx/policy-desk
  apps/api              Spring Boot 3（Java 17 目标，跑在 JDK 25）
  apps/web              Vue 3 + TS + Vite（无 UI 组件库）
  deploy                docker compose（web/api/ai/mysql/redis）+ nginx + .env.example
  docs                  简历三版、录像脚本、发布清单、BOSS 话术
  scripts               demo-seed.ps1（演示语料）、push-github.ps1（推送前预检）

D:\_review_rag-cs       ← 算法层（234 个提交）        github.com/inwardzyx/campus-policy-rag
  service.py            FastAPI 单进程服务（检索/精排/生成/拒答/审计）
  kb/                   loader / chunking / runstate / attribution / loopgate
  evalset/              评测集 + 报告产物（ARTIFACTS 单里登记的才算）
  eval/                 promptfoo 评测壳（held-out 105 题基线；入口 eval/README.md）
  DECISIONS.md          为什么用现成工具 / 补哪三块 / 明确不做什么（面试材料）
  evalset/heldout/questions.sha256  评测集校验和（改题必须一起更新，pre-push 拦）
  scripts/              24 个变异脚本、各类检查器、评测运行器
  scripts/hooks/        pre-commit（数字对账）、pre-push（6 道检查）
```

**依赖关系**：`policy-desk` 通过 `docs/DocumentsClient` 以 `/internal/*` + 内部口令调用算法层。
**部署要求两个仓库并排克隆**（compose 里 ai 的 build context 是 `../../_review_rag-cs`）。

## 3. 已完成（有证据）

| 事项 | 证据 |
|---|---|
| 三层架构 + 权限 + 配额 + 审计 | 63 条 Java 测试、11 条前端测试，`mvn test` / `npm test` 可复算 |
| 端到端权限对比 | 同问题：hr 命中薪酬条款并带引用，employee 拒答；employee 问年假能答 |
| 配额 | 上限 2 时第 3 次 429 + `Retry-After`，审计留下 `quota_denied` |
| 部署 | compose 五容器 healthy；经 nginx 登录/问答/上传/删除全通；问答 ≈4.0s |
| CI | 两个仓库均绿（policy-desk 3 个 job、campus-policy-rag 3 个 job） |
| 算法层 | 462 passed / 27 skipped（27 条 skip 全是"本机无 Redis"） |
| 评测基线（held-out 105 题） | `eval/reports/BASELINE-offline.md`（30/44 · 42/44 · 12/61）+ `BASELINE-online.md`（40/44 · 42/44 · 41/61）；每份报告头带 `kb/runstate.py` 运行指纹；promptfoo 与 `run_eval.py` 同刻逐项一致 |
| 判据收尾 | held-out 校验和 + checker + 常驻变异（④）；指纹进报告（②）；`eval/gate.py` 再导出 `kb/loopgate`；`eval/agreement.py`（n<20 拒绝输出 κ）+ 标注规范 + 20 条示例 |
| 报告新鲜度 | `scripts/check_report_freshness.py`（CI 快 job，`--strict`）：逐份报告比「git sha + docs/kb + docs/inbox 哈希」，四态 + UNVERIFIABLE；被引用且非 FRESH ⇒ exit 1。补的正是 `check_readme_numbers` 管不了的那一半（README ↔ 产物一致 ≠ 产物 ↔ 当前代码一致） |
| 简历 | `docs/resume-final.pdf`（投递）+ `resume-v2-ai-eval.pdf`（技术面）+ PNG/JPG |

## 4. 已经做过的关键决定（**别重新讨论**）

1. **两个项目合并成一个讲**（不并列两个 RAG）。理由：并列会读成"两次重复劳动"。
2. **简历主线 = AI 评测 / AI 应用**，不是"AI 全栈"。理由：评测是她唯一稀缺且学历敏感度低的方向。
3. **简历三版分工**：`resume-final` 投递用（人事/老板看）· `resume-v2-ai-eval` 技术面用 · `resume-hr` 作废。
4. ~~不做 agent 化 / 不做完整 harness / 不做独立沙箱项目~~ → **2026-11-12 被用户方向取代**：目标改为 **AI Agent 全栈**（一个人端到端交付 agent 应用）。原决定的理由仍然成立（瓶颈在"被读到"），所以**只做产品化那一刀**：把已有的 agent 实验（三种 loop + MCP 工具）接成 `/agent`、加前端时间线、把 agent 指标进评测；不换框架、不做多 agent、不做记忆/沙箱。计划见 `_review_rag-cs/agent/PLAN.md`。
5. **不做数据中台**（不造数仓/Flink），只做"评测需要的那层数据底座"。
6. **评测中台策略已改**：用 promptfoo（27k star）打底跑基线 → 只自研 3 块（McNemar 配对门禁、业务判据、失败归因），并写清"为什么不用现成"。
7. **不代投 BOSS**（风控封号风险 + 需要登录态）。只做选岗/话术/台账，发送由用户本人执行。
8. **对外叙事定为「评测资产版本化 + 运行指纹」，不说"数据中台/评测中台"**（2026-11-11 盲审）：
   中台是资深平台团队的词，按那个标准会被追问多租户/调度/治理委员会；本项目该被追问的是判据会不会响。
9. **不做 `git bisect`**：入口/语义要逐版本验证，bisect 的前提（入口一致）本身得先被证明 —— 已用
   worktree 三组合实测替代（`eval/reports/repro-old-code.md`）。

## 5. 待办（按优先级）

| # | 事项 | 判据 |
|---|---|---|
| 1 | ✅ 用 promptfoo 把 105 题跑出基线（2026-11-11） | 两份分数已落盘 `eval/reports/BASELINE-offline.md` / `BASELINE-online.md`；`summarize.py --compare` 与 `run_eval.py` 同刻逐项一致，且把对数改坏会红（变异用例验过） |
| 2 | ✅/⏳ `eval/gate.py`（再导出 `kb/loopgate`，只出翻转表 + p，不判该不该发）✅；`eval/agreement.py`（n<20 或 pe=1 拒绝输出 κ）✅，**标注等人做** ⏳ | 门禁跨层拒绝 + 翻转变单边 p 变小（`tests/test_eval_gate.py`）；κ 三条拒绝（`tests/test_eval_agreement.py`） |
| 3 | ✅ 写 `DECISIONS.md`：为什么用现成工具 + 补哪三块 + 明确不做什么 | 一页，`_review_rag-cs/DECISIONS.md`，含修正后的 replay 理由与 35/44 案例 |
| 4 | 恢复投递（BOSS 直聊，话术见 `docs/boss-greeting.md`） | 一周投 75–100 条，统计回复率 |
| 5 | 等用户提供 **10 个投过的 JD** 做匹配诊断 | 输出"白投了多少个"的结论 |
| 6 | **Agent 产品化**（2 周）：`/agent` + `/agent/stream` → 前端时间线 → agent 指标进评测 | 拒答不退化（≥39/61）才进前端；计划与判据见 `_review_rag-cs/agent/PLAN.md` |

> ⚠️ 做第 1 项时顺带发现：`evalset/report-vector-heldout.md`（落款 2026-10-10）里的数**已经复现不出来**
> —— 同一套 105 题，旧报告 Recall@5 35/44 / 拒答 9/61，今天同一份代码是 30/44 / 12/61
> （`report-rerank-heldout.md` 同样有偏移）。成因未逐条定位（没做 `git bisect`），**只报事实不做归因**。
> ⇒ README 里引它们的那些数因此过期；建议在做第 2/3 项时一并处理，别单独去手改数字。
> 另外 `evalset/run_eval.py` 原来是坏的（调了 FastAPI 端点 `chat()`，一跑就 TypeError），
> 已修 + 补了回归用例 —— 否则"能不能复算"这件事本身就是假的。

## 6. 坑清单（**这一节最重要，全部真实踩过**）

### 6.1 PowerShell / Windows

| 症状 | 原因 | 解法 |
|---|---|---|
| `.ps1` 在 `powershell.exe` 里报"表达式或语句中包含意外的标记"，中文全乱 | 文件存成 **UTF-8 无 BOM**，Windows PowerShell 5.1 按 GBK 解码 | 存成 **UTF-8 with BOM**。CI 已加哨兵（少 BOM 直接红） |
| 用 `edit`/`write` 工具改完 `.ps1` 后**BOM 又没了** | 工具写回时不带 BOM | 每次改完都要重新补 BOM（本仓库 CI 会拦住） |
| `git push` 失败时刷一屏 `CategoryInfo / FullyQualifiedErrorId` | 5.1 把 native stderr 当异常记录打印 | 用 `cmd /c "git push ... 2>&1"` 捕获；**不要**用 `SilentlyContinue`（会连内容一起丢） |
| 冷启动脚本报 `error: No such remote 'origin'` 并直接退出 | `git remote get-url` 无 origin 时写 stderr + `ErrorActionPreference=Stop` | 改用 `git remote` 列表判断 |
| 脚本"推送失败"提示让去建仓库，但仓库其实存在 | 脚本把任何失败都当成"仓库不存在"（真实原因是 pre-push 钩子拦下） | 按输出内容分类：含 `pre-push` → 钩子；含 `Repository not found` → 仓库；否则认证/网络 |
| 测试脚本时把**真仓库**的 origin 改了 | 脚本默认 `LocalPath=D:\policy-desk`，测试时忘传 `-LocalPath` | 测试一律传 `-LocalPath` 到临时目录 |
| `Start-Process` 接管道后命令永不返回 | 子进程继承 stdout 句柄 | 不要把 `Start-Process` 接进管道（见 `~/.dsh/AGENTS.md`） |

### 6.2 PDF / 图片导出

| 症状 | 原因 | 解法 |
|---|---|---|
| 改了 HTML 重新导出，PDF 内容**没变**，但命令不报错 | 目标 PDF 被 PDF 阅读器占用，Edge 写不进去 → **静默失败** | 导出前先探锁（`[IO.File]::Open(...,'None')`）；换文件名；**导出后必须回读校验** |
| `--print-to-pdf` 出错但看不到 | 只有加 `--user-data-dir=<独立目录>` 才会打印 `Failed to write file` | 每次导出用独立 `--user-data-dir`，并抓 `Failed to write` |
| PyMuPDF 装到隔离目录仍 `DLL load failed` | 缺 VC++ 运行库 / 与 Python 3.14 不匹配 | **改用 Edge `--screenshot`** 出 PNG（同一份 HTML，排版与 PDF 一致） |
| JPG 质量 90 反而比 PNG 大 | 白底文字 PNG 压缩更有效 | JPG 降采样到 1600 宽 + 质量 82（≈500KB） |
| 图片版和 PDF 排版不一致 | 屏幕媒体没有 `@page` 边距 | 复制一份 HTML，屏幕下加 `body{width:210mm;min-height:297mm;padding:11mm 12mm}` |

### 6.3 网络

| 症状 | 原因 | 解法 |
|---|---|---|
| `github.com:443` 连不上，但 `api.github.com` 正常 | 国内网络对 github.com 的间歇阻断 | **重试 2–3 次即成**（脚本里已带重试） |
| GitHub Actions 作业日志拉不到（HTTP 403） | 日志端点要 token | 让用户从网页复制失败步骤的日志 |
| `raw.githubusercontent.com` fetch 报"非公网 IP" | 本环境 DNS 把它解析到内网 | 改 fetch `github.com/.../blob/...` 页面 |

### 6.4 Docker / 部署

| 症状 | 原因 | 解法 |
|---|---|---|
| compose 报 `port is already allocated` | 本机 8080 被 **WSL 的 wslrelay** 占用（不能杀） | web 端口用 `8088`（`${WEB_PORT:-8088}`） |
| `nginx -t` 在独立容器里失败 `host not found in upstream "api"` | nginx 加载配置时就要解析 upstream，而 `api` 只在 compose 网络里存在 | `docker run --add-host api:127.0.0.1 ...` |
| 拉起整套时 `localhost:8088` 不通 | Docker Desktop 被关闭，容器全停 | 先开 Docker Desktop，再 `docker compose up -d`（在 `deploy/`） |
| 上传 PDF 报 413 | nginx 默认 1MB，而后端是 20MB | `client_max_body_size 20m` 已对齐 |

### 6.5 算法层（`_review_rag-cs`）的坑

| 症状 | 原因 | 解法 |
|---|---|---|
| pre-push 拦下推送，说"有产物没登记" | `check_artifact_sanity.py` 会反查磁盘产物 vs ARTIFACTS | 登记产物并配该类型的判据（已加"类型七：onboard 报告"三条不变量） |
| `mutate_check_numbers.py` 报"变异没注入成功" | 硬编码了 `现在一共 **7 个**`，端点涨到 21 后原文匹配不上 | 已改为从 README 现读当前数再 +1 |
| CI 快 job `ModuleNotFoundError: yaml` | 该 job 只装 pytest+redis，而 `check_test_counts.py` 要解析 ci.yml | 已加 `pyyaml` 到该 job 的 pip install |
| CI 慢 job 15 个用例 pydantic 报 "DEEPSEEK_API_KEY must be set" | 这些用例会构造真 `ChatDeepSeek`，构造即要求 key | 已给慢 job 配**假 key**（真调用会 401，CI 里仍无真密钥） |
| 本地每跑一次 pytest 就扣一次 API 费用 | `test_cache_is_on_by_default_with_rollback_switch` **真的联网** | 已用 monkeypatch 把 `_invoke_llm` 换成固定返回 |
| 本机 `pytest` 里「扫描版 PDF 必须报错」变红，CI 绿 | 本机装了 **docling**（2026-10-23 装，`requirements.txt` 里**没有**它），`_read_pdf` 的 `auto` 分支回退到版面解析、真解析出正文 | 不要把测试 `skipif` 掉（那会让不变量在有后端的机器上永久消失）⇒ **按环境分派断言**：无后端必须抛 `ParseError`；有后端要么给非空文本、要么明确报错，**不许静默空**。可选依赖声明见 `requirements-optional.txt` |
| 中文输出在管道里变乱码 | GBK 管道 | 脚本已 `sys.stdout.reconfigure(encoding="utf-8", errors="replace")` |
| 新鲜度检查一上线，**每份报告都 STALE** | 规格写的「报告头 sha == HEAD」对**已入库**报告走不通：报告在 HEAD=X 生成、作为新提交 C 入库后 HEAD=C≠X | 改判据为「sha 相同 **或**（是祖先 **且** `git diff sha..HEAD` 里没有影响结果的文件）」。排除清单是**排除式**：清单外一律算影响 ⇒ 宁可误报 |
| 指纹来源坏掉时，检查器**判 FRESH** | `capture()` 返回空 ⇒ 当前语料哈希是空串，而 `"abc".startswith("")` 恒真 ⇒ 「语料没变」凭空成真 | 拿不到 docs/kb 或 docs/inbox 的当前哈希时，一律判 `UNVERIFIABLE`（自测判据 ⑥ 就是抓这个的） |
| CI 里新鲜度检查**必假红** | actions/checkout 默认 fetch-depth=1，`git diff <报告 sha>..HEAD` 拿不到祖先提交 | 快 job checkout 设 `fetch-depth: 0`；检查器另识别浅克隆并提示 `git fetch --unshallow`（免得人去改报告） |
| `evalset/report-*.md` 里的数**复现不出来**（跑一次就 TypeError） | 脚本调 `svc.chat(req)`，而 `chat()` 自 2026-10-13 起是 FastAPI 端点（签名要两个参数）⇒ 整个 `run_eval.py` 一跑就崩；**CI 没跑过它**，所以屏幕上看不出来 | 改调 `handle_chat()`（service.py 给"直连管道"留的入口）；已补回归用例 `test_run_eval_uses_handle_chat`，把入口改回去必红 |

## 7. 常用命令

```powershell
# ---- 启动（两种，任选其一）----
# A. 宿主三进程（Python 8028 / Java 8081 / 前端 5174）
D:\policy-desk\dev.ps1
D:\policy-desk\stop-dev.ps1

# B. compose 五容器（需先开 Docker Desktop）
cd D:\policy-desk\deploy
docker compose up -d
docker compose ps

# ---- 测试 ----
cd D:\policy-desk\apps\api ; mvn test            # 63 条（需 JAVA_HOME=D:\idea）
cd D:\policy-desk\apps\web ; npm run test        # 4 条
cd D:\_review_rag-cs ; D:\Python-project\.venv\Scripts\python.exe -m pytest -q   # 462 passed

# ---- 导出简历 ----
cd D:\policy-desk\docs
$edge="C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe"
& $edge --headless=new --disable-gpu --no-pdf-header-footer --print-to-pdf="D:\policy-desk\docs\resume-final.pdf" "file:///D:/policy-desk/docs/resume-final.html"
# 图片版：先由 resume-final.html 生成 resume-final-img.html（加 A4 屏幕样式），再
& $edge --headless=new --disable-gpu --hide-scrollbars --force-device-scale-factor=3 --window-size=794,1123 --screenshot="D:\policy-desk\docs\resume-final.png" "file:///D:/policy-desk/docs/resume-final-img.html"

# ---- 推送（带预检；网络抖就重试）----
cd D:\policy-desk
.\scripts\push-github.ps1                 # policy-desk
.\scripts\push-github.ps1 -Repo campus-policy-rag -LocalPath D:\_review_rag-cs
```

## 8. 环境事实

```text
Python venv   D:\Python-project\.venv\Scripts\python.exe   （3.14.6）
JDK           D:\idea（JDK 25，pom 里 release=17）
Maven         D:\policy-desk\tools\apache-maven-3.9.9\bin\mvn.cmd（settings: tools/settings.xml）
Edge          C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe
cloudflared   C:\Program Files (x86)\cloudflared\cloudflared.exe
MySQL         3306，库 policy_desk，用户 policy / policy_dev_2026
演示账号      hr_demo / emp_demo，口令 policy123
npm registry  npmmirror
```

## 9. 文件地图（哪里找什么）

```text
docs/resume-final.html       ← 简历真源，改内容只改这里，PDF/PNG 都从它生成
docs/resume-v2-ai-eval.pdf   ← 技术面版（评测方法论前置）
docs/demo-script.md          ← 3 分钟录像逐字稿 + 三句已实测的演示问题
docs/boss-greeting.md        ← BOSS 打招呼话术（三岗位各一套）+ 发送纪律
docs/publish.md              ← 推 GitHub 的操作清单 + 推送前自检
scripts/demo-seed.ps1        ← 演示语料（demo 分区：public 年假 + hr 加班补贴）
scripts/push-github.ps1      ← 推送预检（.env / key / 大文件）
deploy/docker-compose.yml    ← 五容器编排（web 只暴露 8088）
```

## 10. 新窗口的第一句话建议

```text
「读 D:\policy-desk\HANDOFF.md，然后我们继续做第 5 节待办里的第 1 项。」
```

如果要让新会话快速进入状态，让它先跑这三条确认环境没坏：

```powershell
cd D:\policy-desk ; git log --oneline -3 ; git status --porcelain
cd D:\_review_rag-cs ; git log --oneline -3 ; git status --porcelain
cd D:\policy-desk\apps\web ; npm run test
```

---

## 11. 一句话总纲

> 这个项目技术上已经够用了，**接下来所有的价值都在"被看见"上**：
> 投出去、被点开、被约面试。别再往代码里加东西，除非面试反馈要求加。
