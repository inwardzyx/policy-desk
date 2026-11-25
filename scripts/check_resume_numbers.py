# -*- coding: utf-8 -*-
"""简历上的数字必须能当场打开给人看 —— 这个脚本把每个数字的锚点跑一遍。

## 为什么需要它

2026-11-16 用户当场验简历，找出三个"找不到锚"的数：

| 简历原话 | 实际 | 病根 |
|---|---|---|
| `for rnd in range(6)` | `for rnd in range(rounds_limit)`（`MAX_ROUNDS = 6`） | 写成了**早期快照**，代码改了简历没跟 |
| 473 条算法层测试 | `pytest --collect-only` = 541 | 加了几十个 agent 用例后没更新 |
| 16 条前端单测 | `npm test` = **16** ✓ | 数是对的，但没人能一眼复算 |

⇒ 规矩：**简历里每个数字都要有一条命令能现算**，并且这个脚本能判红。

## 现在核哪些

后端 `@Test` 数 / 前端 vitest 数 / 算法层 pytest 收集数 /
场景套件数与用例数（`evalset/scenarios/*.jsonl`）/ 盲出独立集题数 /
`for rnd in range(...)` 的写法。

## 怎么用

```powershell
D:\\Python-project\\.venv\\Scripts\\python.exe scripts\\check_resume_numbers.py
```

退出码 0 = 全部对得上；1 = 有数字/措辞与代码不一致（会打印"简历写 X，实际 Y"）。
"""
from __future__ import annotations

import os
import json
import re
import subprocess
import sys
from pathlib import Path

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

ROOT = Path(__file__).resolve().parent.parent
RESUME = ROOT / "docs" / "resume-final.html"
RAG = Path(os.environ.get("RESUME_CHECK_RAG_DIR", r"D:\_review_rag-cs"))
PY = r"D:\Python-project\.venv\Scripts\python.exe"
WEB = ROOT / "apps" / "web"


def _text(p: Path) -> str:
    return p.read_text(encoding="utf-8")


def count_java_tests() -> int:
    """后端测试数：数 `@Test` 注解（没有 mvn 时这是最接近的锚）。"""
    n = 0
    for f in (ROOT / "apps" / "api" / "src" / "test").rglob("*.java"):
        n += len(re.findall(r"@Test\b", _text(f)))
    return n


def count_frontend_tests() -> int:
    """前端测试数：**vitest 自己报的数**。

    ★ 不要解析人读的那行 `Tests 16 passed`：本地 TTY 有、CI（非 TTY）下格式不同，
      2026-11-16 CI 上就是这么失败的（拿不到数 → 脚本报错）。
      改成让 vitest 直接输出 JSON（`--reporter=json`），读 `numTotalTests` —— 与环境无关。
    """
    import tempfile

    out = Path(tempfile.mkdtemp()) / "vitest.json"
    # ★ shell=True 只能配**字符串**：给 list 时 POSIX 会把第一个元素当整条命令，
    #   剩下的当 sh 自己的参数 ⇒ 在 Linux（CI）里 npx 收不到 --reporter，JSON 永远不生成
    #   （2026-11-16 CI 第二次失败就栽在这）。
    cmd = f'npx vitest run --reporter=json --outputFile="{out}"'
    r = subprocess.run(cmd, cwd=str(WEB), capture_output=True, text=True,
                       encoding="utf-8", errors="replace", shell=True)
    if out.exists():
        try:
            data = json.loads(out.read_text(encoding="utf-8"))
            n = data.get("numTotalTests")
            if isinstance(n, int):
                return n
        except Exception:                                  # noqa: BLE001
            pass
    # 兜底：老路径（TTY 环境），拿不到就明确报错，不返回 0
    m = re.search(r"Tests\s+(\d+)\s+passed", (r.stdout or "") + (r.stderr or ""))
    if not m:
        raise RuntimeError("拿不到 vitest 的测试数（JSON 与人读输出都没解析出来）")
    return int(m.group(1))


def external_available() -> bool:
    """算法层锚点在**另一个仓库**里。CI 只 checkout 本仓库 ⇒ 必须显式降级，不许静默当 0。"""
    return (RAG / "tests").exists() and Path(PY).exists()


def count_pytest_collected() -> int:
    """算法层测试数：pytest **收集数**（不是"跑过的"，收集数与环境无关、可复算）。"""
    r = subprocess.run([PY, "-m", "pytest", "--collect-only", "-q"], cwd=str(RAG),
                       capture_output=True, text=True, encoding="utf-8", errors="replace")
    m = re.search(r"(\d+) tests? collected", (r.stdout or "") + (r.stderr or ""))
    if not m:
        raise RuntimeError("拿不到 pytest 收集数")
    return int(m.group(1))


def count_suites() -> tuple[int, int]:
    """场景套件数 + 用例数：`evalset/scenarios/*.jsonl`，按文件数与非空行数现算。"""
    d = RAG / "evalset" / "scenarios"
    files = sorted(d.glob("*.jsonl"))
    n_cases = 0
    for f in files:
        n_cases += sum(1 for line in _text(f).splitlines() if line.strip())
    return len(files), n_cases


def count_heldout() -> int:
    """盲出独立集题数：`evalset/heldout/questions.json` 里 questions 的条数。"""
    import json as _json

    data = _json.loads(_text(RAG / "evalset" / "heldout" / "questions.json"))
    return len(data.get("questions") or data)


def loop_anchor_ok() -> tuple[bool, str]:
    """简历里那句 `for rnd in range(...)` 必须与代码逐字对得上。"""
    loop = _text(RAG / "agent" / "loop.py")
    m = re.search(r"for rnd in range\(([^)]+)\)", loop)
    if not m:
        return False, "agent/loop.py 里找不到 `for rnd in range(...)`"
    actual = m.group(1).strip()
    probe = _text(RAG / "mcp" / "probe_loop.py")
    max_rounds = re.search(r"^MAX_ROUNDS = (\d+)", probe, re.M)
    return True, f"{actual}（默认 {max_rounds.group(1) if max_rounds else '?'} 轮）"


def _round_half_up(x: float) -> int:
    """四舍五入（Python 内置 round 是 half-to-even：round(178.5) == 178）。"""
    return int(x + 0.5) if x >= 0 else -int(-x + 0.5)


def check_noisy_neighbor(plain: str) -> tuple[bool, str]:
    """简历里的 652 / 845 / 179 / 39 / 292 / 48 全部从 round2 产物现算。

    ★ 为什么单独一条：这组数字以前散在 HANDOFF 与 service-reliability.md 里，
      两份文档口径不同（+652% vs +623%），简历又是第三种写法（只写 ms 不写百分比）。
      数字一分散，就一定会漂。
    """
    import json as _json
    data = _json.loads(_text(RAG / "experiments" / "noisy-neighbor-round2.json"))
    arms = {a["arm"]: a for a in data["arms"]}

    r0 = arms["R0_现状_端点准入"]
    base_p95 = _round_half_up(r0["phases"]["1_基线_B单独"]["p95"])       # 38.8 → 39
    noisy_p95 = _round_half_up(r0["phases"]["2_噪音_A打满"]["p95"])      # 291.9 → 292
    conc = r0["测量参数"]["a_conc"]                                       # 48
    deg_base = _round_half_up(r0["degradation_pct"])                      # 652
    deg_worker = _round_half_up(arms["A5b_4worker"]["degradation_pct"])   # 845
    deg_final = _round_half_up(arms["R3_修正配置_客户端退避200ms"]["degradation_pct"])  # 179

    expect = {
        "并发数": (rf"{conc}\s*并发", conc),
        "基线 p95": (rf"从\s*{base_p95}\s*ms|{base_p95}\s*ms", base_p95),
        "噪音 p95": (rf"{noisy_p95}\s*ms", noisy_p95),
        "劣化 +652%": (r"\+(\d+)%", deg_base),
        "4 worker +845%": (rf"\+{deg_worker}%", deg_worker),
        "收敛 +179%": (rf"\+{deg_final}%", deg_final),
    }
    missing = [k for k, (pat, _) in expect.items() if not re.search(pat, plain)]
    if missing:
        return False, f"简历里缺这些锚：{missing}（产物值 conc={conc}, p95={base_p95}→{noisy_p95}, "
    # 逐个值必须在简历里出现
    for label, (pat, val) in expect.items():
        if label in ("劣化 +652%", "4 worker +845%", "收敛 +179%"):
            if not re.search(rf"\+{val}%", plain):
                return False, f"{label}：简历里没有 +{val}%"
        elif str(val) not in plain:
            return False, f"{label}：简历里没有 {val}"
    return True, (f"并发 {conc} / p95 {base_p95}→{noisy_p95} / "
                  f"+{deg_base}% / 4worker +{deg_worker}% / 收敛 +{deg_final}%")


def main() -> int:
    import argparse

    ap = argparse.ArgumentParser()
    ap.add_argument("--allow-missing-external", action="store_true",
                    help="外部仓库（campus-policy-rag）不在时，跳过算法层与循环锚点，而不是报错")
    args = ap.parse_args()

    html = _text(RESUME)
    # ★ 去掉标签再匹配：加粗会把 "63 条后端" 切成 "<b>63</b> 条后端"，
    #   直接对 HTML 正则匹配会漏掉（2026-11-16 给关键数字加粗时当场踩到）。
    plain = re.sub(r"<[^>]+>", "", html)
    problems: list[str] = []

    java = count_java_tests()
    fe = count_frontend_tests()
    have_ext = external_available()
    if have_ext:
        py = count_pytest_collected()
        n_suites, n_cases = count_suites()
        n_heldout = count_heldout()
        ok, loop = loop_anchor_ok()
    elif args.allow_missing_external:
        py, loop = None, None
    else:
        print("✗ 找不到外部仓库（campus-policy-rag）或解释器 —— "
              "算法层与循环锚点无法核对。CI 里请加 --allow-missing-external。")
        return 1

    def claim(pattern: str, expect: int, label: str) -> None:
        m = re.search(pattern, plain)
        if not m:
            problems.append(f"{label}：简历里找不到这句（{pattern}）")
            return
        got = int(m.group(1))
        if got != expect:
            problems.append(f"{label}：简历写 {got}，实际 {expect}")

    claim(r"(\d+) 条后端", java, "后端测试数")
    claim(r"(\d+) 条前端", fe, "前端单测数")
    if py is not None:
        claim(r"(\d+) 条算法层", py, "算法层测试数")
        claim(r"(\d+) 个场景套件", n_suites, "场景套件数")
        claim(r"(\d+) 条场景用例", n_cases, "场景用例数")
        claim(r"(\d+) 题盲出独立集", n_heldout, "盲出独立集题数")
    else:
        print("SKIP 算法层测试数（外部仓库不在；CI 只 checkout 本仓库）")

    # ---- noisy-neighbor 那组数字：从产物现算，不许手写 ----
    if have_ext:
        ok_nn, msg_nn = check_noisy_neighbor(plain)
        print(f"noisy-neighbor        = {msg_nn}")
        if not ok_nn:
            problems.append(msg_nn)
    else:
        print("SKIP noisy-neighbor（外部仓库不在）")

    if loop is not None:
        if f"range({loop.split('（')[0]})" not in plain:
            problems.append(f"循环写法：简历里没写 `for rnd in range({loop.split('（')[0]})` —— 与代码不一致")
    else:
        print("SKIP 循环写法（同上）")

    print(f"后端 @Test           = {java}")
    print(f"前端 vitest          = {fe}")
    print(f"算法层 pytest 收集    = {py if py is not None else 'SKIP（外部仓库不在）'}")
    if py is not None:
        print(f"场景套件 / 用例       = {n_suites} 个 / {n_cases} 条")
        print(f"盲出独立集            = {n_heldout} 题")
    print(f"循环锚点             = {loop if loop is not None else 'SKIP'}")

    if problems:
        print("\n✗ 简历与代码对不上：")
        for p in problems:
            print("  -", p)
        return 1
    print("\n✓ 三个数字 + 循环写法都与代码一致")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
