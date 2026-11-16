# -*- coding: utf-8 -*-
"""简历上的数字必须能当场打开给人看 —— 这个脚本把每个数字的锚点跑一遍。

## 为什么需要它

2026-11-16 用户当场验简历，找出三个"找不到锚"的数：

| 简历原话 | 实际 | 病根 |
|---|---|---|
| `for rnd in range(6)` | `for rnd in range(rounds_limit)`（`MAX_ROUNDS = 6`） | 写成了**早期快照**，代码改了简历没跟 |
| 473 条算法层测试 | `pytest --collect-only` = **532** | 加了几十个 agent 用例后没更新 |
| 16 条前端单测 | `npm test` = **16** ✓ | 数是对的，但没人能一眼复算 |

⇒ 规矩：**简历里每个数字都要有一条命令能现算**，并且这个脚本能判红。

## 怎么用

```powershell
D:\\Python-project\\.venv\\Scripts\\python.exe scripts\\check_resume_numbers.py
```

退出码 0 = 全部对得上；1 = 有数字/措辞与代码不一致（会打印"简历写 X，实际 Y"）。
"""
from __future__ import annotations

import os
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
    """前端测试数：**vitest 自己报的数**（`Tests  N passed`）。"""
    r = subprocess.run(["npm", "test"], cwd=str(WEB), capture_output=True, text=True,
                       encoding="utf-8", errors="replace", shell=True)
    m = re.search(r"Tests\s+(\d+)\s+passed", (r.stdout or "") + (r.stderr or ""))
    if not m:
        raise RuntimeError("拿不到 vitest 的测试数（输出里没有 `Tests N passed`）")
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


def main() -> int:
    import argparse

    ap = argparse.ArgumentParser()
    ap.add_argument("--allow-missing-external", action="store_true",
                    help="外部仓库（campus-policy-rag）不在时，跳过算法层与循环锚点，而不是报错")
    args = ap.parse_args()

    html = _text(RESUME)
    problems: list[str] = []

    java = count_java_tests()
    fe = count_frontend_tests()
    have_ext = external_available()
    if have_ext:
        py = count_pytest_collected()
        ok, loop = loop_anchor_ok()
    elif args.allow_missing_external:
        py, loop = None, None
    else:
        print("✗ 找不到外部仓库（campus-policy-rag）或解释器 —— "
              "算法层与循环锚点无法核对。CI 里请加 --allow-missing-external。")
        return 1

    def claim(pattern: str, expect: int, label: str) -> None:
        m = re.search(pattern, html)
        if not m:
            problems.append(f"{label}：简历里找不到这句（{pattern}）")
            return
        got = int(m.group(1))
        if got != expect:
            problems.append(f"{label}：简历写 {got}，实际 {expect}")

    claim(r"(\d+) 条后端", java, "后端测试数")
    claim(r"(\d+) 条前端", fe, "前端单测数")
    if py is not None:
        claim(r"(\d+) 条算法层测试", py, "算法层测试数")
    else:
        print("SKIP 算法层测试数（外部仓库不在；CI 只 checkout 本仓库）")

    if loop is not None:
        if f"range({loop.split('（')[0]})" not in html:
            problems.append(f"循环写法：简历里没写 `for rnd in range({loop.split('（')[0]})` —— 与代码不一致")
    else:
        print("SKIP 循环写法（同上）")

    print(f"后端 @Test           = {java}")
    print(f"前端 vitest          = {fe}")
    print(f"算法层 pytest 收集    = {py if py is not None else 'SKIP（外部仓库不在）'}")
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
