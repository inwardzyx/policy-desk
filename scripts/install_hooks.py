# -*- coding: utf-8 -*-
"""把 `scripts/hooks/*` 装进 `.git/hooks/`（幂等）。

## 为什么需要安装脚本

`.git/hooks/` **不进仓库**，新 clone 默认一个钩子都没有 —— 而本仓库的分工是
「CI 只喊、本地钩子才拦」。不装 = 那句"提交前会检查"是空话。

## 用法

```powershell
D:\\Python-project\\.venv\\Scripts\\python.exe scripts\\install_hooks.py
```

跑完会看到 `[ok] pre-commit 已安装`。重复跑是安全的（内容一样就不动）。
"""
from __future__ import annotations

import os
import shutil
import sys
from pathlib import Path

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "scripts" / "hooks"
DST = ROOT / ".git" / "hooks"

HEADER = "#!/usr/bin/env bash\n"


def main() -> int:
    if not DST.exists():
        print(f"✗ 找不到 {DST}：这里不是 git 仓库？")
        return 1
    if not SRC.exists():
        print(f"✗ 找不到钩子源目录 {SRC}")
        return 1

    installed = 0
    for src in sorted(SRC.iterdir()):
        if not src.is_file() or src.name.startswith("."):
            continue
        dst = DST / src.name
        if dst.exists() and dst.read_bytes() == src.read_bytes():
            print(f"[skip] {src.name} 已是最新")
            continue
        shutil.copyfile(src, dst)
        # ★ Windows 上 chmod 对 git 是否"可执行"没影响（Git for Windows 按 shebang 走 sh），
        #   但在 WSL/macOS 上它是必须的 —— 两种环境都要能跑。
        os.chmod(dst, 0o755)
        # ★ 行尾必须是 LF：CRLF 会让 shebang 变成 `bash\r`，钩子直接跑不起来
        #   （本仓库另一处踩过同款：容器里的 python 脚本）。
        data = dst.read_bytes().replace(b"\r\n", b"\n")
        dst.write_bytes(data)
        installed += 1
        print(f"[ok] {src.name} 已安装")

    if not installed:
        print("没有需要安装的钩子。")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
