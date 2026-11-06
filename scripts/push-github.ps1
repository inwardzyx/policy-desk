<#
.SYNOPSIS
    把仓库推到 GitHub，并在推之前把"会被打回的"问题先拦下来。

.DESCRIPTION
    ★ 为什么要这个脚本：推送前有三类事故，事后都难救 ——
      ① .env / 密钥进了公开仓库（公开仓库的历史是永久的，删了也还在别人 fork 里）；
      ② 大文件进历史（>50MB 会被 GitHub 拒收，事后要从历史里挖掉很麻烦）；
      ③ 仓库建成了 Private 却忘了给面试官开权限 —— 等于没做。

.PARAMETER Repo
    远端仓库名。默认 policy-desk。

.PARAMETER LocalPath
    本地仓库路径。默认 D:\policy-desk。

.PARAMETER GitHubUser
    你的 GitHub 用户名。默认从 git config 里读。

.PARAMETER DryRun
    只做检查，不真的 push。

.EXAMPLE
    .\scripts\push-github.ps1 -DryRun
    .\scripts\push-github.ps1
    .\scripts\push-github.ps1 -Repo review-rag-cs -LocalPath D:\_review_rag-cs
#>
param(
    [string]$Repo = 'policy-desk',
    [string]$LocalPath = 'D:\policy-desk',
    [string]$GitHubUser = '',
    [switch]$DryRun
)

$ErrorActionPreference = 'Stop'

if (-not $GitHubUser) {
    $GitHubUser = (git config --global user.name)
    if (-not $GitHubUser) { throw '读不到 git 用户名：加 -GitHubUser 你的用户名' }
}

Write-Host "== 目标：github.com/$GitHubUser/$Repo （本地 $LocalPath）==" -ForegroundColor Cyan
if (-not (Test-Path (Join-Path $LocalPath '.git'))) { throw "$LocalPath 不是 git 仓库" }

Push-Location $LocalPath
$fail = 0

# ---------------------------------------------------------------- ① 工作树
$dirty = git status --porcelain
if ($dirty) {
    Write-Host "  [warn] 工作树有未提交改动（推送只会带上已提交的部分）：" -ForegroundColor Yellow
    $dirty | Select-Object -First 5 | ForEach-Object { Write-Host "         $_" -ForegroundColor DarkGray }
}

# ---------------------------------------------------------------- ② 密钥
Write-Host '  [检查] .env 是否被跟踪' -ForegroundColor DarkGray
$envFiles = git ls-files | Where-Object { $_ -match '\.env$' }
if ($envFiles) { Write-Host "  [x] 这些 .env 被跟踪了：$($envFiles -join ', ')" -ForegroundColor Red; $fail++ }
else { Write-Host '  [ok] 没有 .env 被跟踪' -ForegroundColor Green }

Write-Host '  [检查] 形似 API key 的字符串' -ForegroundColor DarkGray
# ★ 不用裸 "sk-"：本仓库 policy-desk-* 全含 sk-，会满屏假红
$hits = git grep -nE "sk-[A-Za-z0-9]{20,}" -- . 2>$null
if ($hits) { Write-Host "  [x] 发现疑似 key：$($hits | Select-Object -First 3)" -ForegroundColor Red; $fail++ }
else { Write-Host '  [ok] 没有形似 API key 的字符串' -ForegroundColor Green }

# ---------------------------------------------------------------- ③ 大文件
Write-Host '  [检查] 超过 50MB 的跟踪文件（GitHub 会拒收）' -ForegroundColor DarkGray
$big = git ls-files | ForEach-Object {
    $f = Get-Item $_ -ErrorAction SilentlyContinue
    if ($f -and $f.Length -gt 50MB) { "$_ ($([math]::Round($f.Length/1MB,1)) MB)" }
}
if ($big) { Write-Host "  [x] $($big -join ', ')" -ForegroundColor Red; $fail++ }
else { Write-Host '  [ok] 没有超限文件' -ForegroundColor Green }

if ($fail -gt 0) {
    Pop-Location
    throw "预检失败 $fail 项，已中止（不要用 --force 绕过）"
}

# ---------------------------------------------------------------- ④ 远端
$url = "https://github.com/$GitHubUser/$Repo.git"
$branch = git rev-parse --abbrev-ref HEAD

# ★ 用 `git remote` 而不是 `git remote get-url origin`：
#   没有 origin 时后者会往 stderr 写 "error: No such remote 'origin'"，
#   而 Windows PowerShell 5.1 在 $ErrorActionPreference='Stop' 下会把它当成**终止性错误**——
#   于是"本来很正常的首推"在 5.1 里直接崩掉（在 pwsh 7 里不会）。这条是实际踩到的。
$remotes = @(git remote)
$current = if ($remotes -contains 'origin') { (git remote get-url origin) } else { '' }

if ($DryRun) {
    Write-Host "  [DryRun] origin 会指向 $url（当前：$(if ($current) { $current } else { '未设置' })）" -ForegroundColor DarkGray
    Write-Host "  [信息] 分支 $branch，提交数 $((git rev-list --count HEAD))" -ForegroundColor DarkGray
    Write-Host ''
    Write-Host '  [DryRun] 到此为止。真实执行会运行：' -ForegroundColor Yellow
    Write-Host "           git remote add/set-url origin $url" -ForegroundColor Yellow
    Write-Host "           git push -u origin $branch" -ForegroundColor Yellow
    Pop-Location
    return
}

# ★ 只在真正执行时才改远端 —— DryRun 不该有副作用（这条是被自己踩出来的：
#   第一版 DryRun 顺手把 origin 加上了，但脚本却宣称"只是检查"）。
if ($current -eq $url) {
    Write-Host "  [ok] origin 已指向 $url" -ForegroundColor Green
} else {
    if ($current) { git remote set-url origin $url } else { git remote add origin $url }
    Write-Host "  [ok] origin → $url" -ForegroundColor Green
}
Write-Host "  [信息] 分支 $branch，提交数 $((git rev-list --count HEAD))" -ForegroundColor DarkGray

# ---------------------------------------------------------------- ⑤ 推送
Write-Host '== 推送 ==' -ForegroundColor Cyan
# ★ git push 会把进度写到 stderr；在 5.1 + ErrorActionPreference=Stop 下那会被当成
#   终止性错误。这里临时放行，改用退出码判定。
$ErrorActionPreference = 'Continue'
git push -u origin $branch
$pushCode = $LASTEXITCODE
$ErrorActionPreference = 'Stop'
if ($pushCode -ne 0) {
    Pop-Location
    throw @'
推送失败。最常见的原因是**远端仓库还不存在**：
  打开 https://github.com/new 建一个名为 <Repo> 的仓库（Public，不要勾 Add a README）
然后再跑一次本脚本。
'@
}

Pop-Location

Write-Host ''
Write-Host '== 接下来看两个页面 ==' -ForegroundColor Cyan
Write-Host "  仓库：  https://github.com/$GitHubUser/$Repo"
Write-Host "  Actions：https://github.com/$GitHubUser/$Repo/actions"
Write-Host ''
Write-Host '  CI 绿了之后再做这两件事（顺序别反）：' -ForegroundColor Yellow
Write-Host '   1) README 顶部取消徽章注释，把 YOUR_GITHUB_NAME 换成用户名'
Write-Host '   2) 两个仓库互相加链接（docs/publish.md 第 4 节）'
