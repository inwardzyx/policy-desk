<#
.SYNOPSIS
    停掉 dev.ps1 起的三个进程（只停自己启动的，按 _run 里记的 PID）。
#>
$ErrorActionPreference = 'Continue'
$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
$RunDir = Join-Path $Root '_run'

# ★ 按 PID 精确停，而不是按进程名（java/node/python）：
#   这台机器上同时跑着别的项目（比如 D:\agent-eval-sandbox\web 的 vite），
#   按名字杀会误伤别人。
foreach ($name in 'web', 'java', 'python') {
    $f = Join-Path $RunDir "$name.pid"
    if (-not (Test-Path $f)) { Write-Host "  $name：没有记录，跳过" -ForegroundColor DarkGray; continue }
    $procId = (Get-Content $f | Select-Object -First 1).Trim()
    if (-not $procId) { continue }
    try {
        # 先杀启动它的 pwsh，再杀进程树里真正的 java/node/python
        Get-CimInstance Win32_Process -Filter "ParentProcessId=$procId" | ForEach-Object {
            Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue
        }
        Stop-Process -Id $procId -Force -ErrorAction SilentlyContinue
        Write-Host "  $name：已停（PID $procId）" -ForegroundColor Green
    } catch {
        Write-Host "  $name：PID $procId 已经不在了" -ForegroundColor DarkGray
    }
    Remove-Item $f -ErrorAction SilentlyContinue
}

foreach ($p in 8028, 8081) {
    $c = Get-NetTCPConnection -LocalPort $p -State Listen -ErrorAction SilentlyContinue
    Write-Host ("  端口 {0}：{1}" -f $p, $(if ($c) { '仍在监听' } else { '已释放' }))
}
