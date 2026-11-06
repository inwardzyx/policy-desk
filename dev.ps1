<#
.SYNOPSIS
    一键启动「制度问答台」的三个进程（Python AI / Java 业务层 / 前端），并等它们真的就绪。

.DESCRIPTION
    ★ 为什么要有这个脚本：手动起三个进程时，任何一句 env 忘了设、任何一个端口被占，
    表现都是"页面能打开但登录不了"，而报错信息在浏览器的 Network 面板里，
    和真正的根因（8081 没起）隔了三层。脚本把这些检查前置到终端里。

    ★ 不接管道：Start-Process 起的进程一旦被接进 PowerShell 管道（| Select-Object 之类），
    父进程会一直等子进程的 stdout 句柄 —— 命令永远不返回。所以日志一律重定向到文件。

.PARAMETER WebPort
    前端端口。默认 5174（5173 常被别的项目的 vite 占着）。

.PARAMETER Dev
    用 `npm run dev`（热更新）而不是构建产物 + preview。

.EXAMPLE
    .\dev.ps1
    .\dev.ps1 -Dev -WebPort 5175
#>
param(
    [int]$WebPort = 5174,
    [switch]$Dev
)

$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
$RunDir = Join-Path $Root '_run'
New-Item -ItemType Directory -Force $RunDir | Out-Null

function Test-Port($port) {
    [bool](Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue)
}

function Wait-Http($url, $name, $seconds = 120) {
    for ($i = 0; $i -lt $seconds; $i++) {
        try {
            $null = Invoke-WebRequest -Uri $url -TimeoutSec 3 -UseBasicParsing -ErrorAction Stop
            Write-Host "  [ok] $name 就绪：$url" -ForegroundColor Green
            return $true
        } catch {
            # 503 也算"起来了"（健康检查在依赖没就绪时会这样），对 Java 尤其重要
            if ($_.Exception.Response -and [int]$_.Exception.Response.StatusCode -eq 503) {
                Write-Host "  [ok] $name 已监听（健康检查 503：依赖还没全好）" -ForegroundColor Yellow
                return $true
            }
            Start-Sleep -Seconds 1
        }
    }
    Write-Host "  [x] $name 在 ${seconds}s 内没就绪，见 $RunDir" -ForegroundColor Red
    return $false
}

# ---------------------------------------------------------------- 前置检查
Write-Host '== 前置检查 ==' -ForegroundColor Cyan
foreach ($p in 8028, 8081) {
    if (Test-Port $p) { throw "端口 $p 已被占用：先运行 .\stop-dev.ps1，或自己查占用进程" }
}
if (Test-Port $WebPort) { throw "端口 $WebPort 已被占用（前端）。换 -WebPort 或先释放它" }

$mysql = 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe'
if (-not (Test-Path $mysql)) { Write-Host '  [warn] 找不到 mysql 客户端，跳过数据库连通性检查' -ForegroundColor Yellow }
else {
    & $mysql -u policy -ppolicy_dev_2026 policy_desk -e 'SELECT 1' 2>&1 | Out-Null
    if ($LASTEXITCODE -ne 0) { throw '连不上 MySQL（policy 用户 / policy_desk 库）。先按 README「初始化数据库」跑一次' }
    Write-Host '  [ok] MySQL 可连（policy / policy_desk）' -ForegroundColor Green
}

# ---------------------------------------------------------------- 起 Python
Write-Host '== 启动 Python AI 服务（8028）==' -ForegroundColor Cyan
$pyCmd = @"
`$env:INTERNAL_TOKEN='dev-token'
`$env:AUTH_MODE='proxy'
`$env:TRUST_PROXY_HEADERS='1'
`$env:RATE_BURST='100'
`$env:RATE_PER_MIN='600'
`$env:DAILY_REQ_QUOTA='1000'
`$env:PYTHONIOENCODING='utf-8'
Set-Location 'D:\_review_rag-cs'
& 'D:\Python-project\.venv\Scripts\python.exe' -m uvicorn service:app --host 127.0.0.1 --port 8028 *> '$RunDir\python.log'
"@
$py = Start-Process pwsh -ArgumentList '-NoProfile', '-Command', $pyCmd -PassThru -WindowStyle Hidden
$py.Id | Set-Content (Join-Path $RunDir 'python.pid')

if (-not (Wait-Http 'http://127.0.0.1:8028/health' 'Python' 180)) { exit 1 }

# ---------------------------------------------------------------- 起 Java
Write-Host '== 启动 Java 业务层（8081）==' -ForegroundColor Cyan
$jar = Join-Path $Root 'apps\api\target\policy-desk-api-0.1.0-SNAPSHOT.jar'
if (-not (Test-Path $jar)) { throw "找不到 $jar —— 先构建：mvn -s tools\settings.xml -f apps\api\pom.xml package" }

$javaCmd = @"
`$env:INTERNAL_TOKEN='dev-token'
`$env:PYTHON_AI_URL='http://127.0.0.1:8028'
`$env:SERVER_PORT='8081'
`$env:DB_URL='jdbc:mysql://127.0.0.1:3306/policy_desk?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true'
`$env:DB_USER='policy'
`$env:DB_PASSWORD='policy_dev_2026'
`$env:JWT_SECRET='dev-secret-please-change-0123456789abcdef'
`$env:SEED_DEMO_USERS='true'
`$env:DEMO_PASSWORD='policy123'
`$env:QUOTA_DAILY_LIMIT='12'
& 'D:\idea\bin\java.exe' -jar '$jar' *> '$RunDir\java.log'
"@
$jv = Start-Process pwsh -ArgumentList '-NoProfile', '-Command', $javaCmd -PassThru -WindowStyle Hidden
$jv.Id | Set-Content (Join-Path $RunDir 'java.pid')

if (-not (Wait-Http 'http://127.0.0.1:8081/actuator/health' 'Java' 120)) { exit 1 }

# ---------------------------------------------------------------- 起前端
Write-Host "== 启动前端（$WebPort）==" -ForegroundColor Cyan
$web = Join-Path $Root 'apps\web'
if ($Dev) {
    $npmScript = "Set-Location '$web'; npx vite --port $WebPort --strictPort *> '$RunDir\web.log'"
} else {
    if (-not (Test-Path (Join-Path $web 'dist\index.html'))) {
        Write-Host '  dist 不存在，先构建…' -ForegroundColor Yellow
        Push-Location $web; npm run build | Out-Null; Pop-Location
    }
    $npmScript = "Set-Location '$web'; npx vite preview --port $WebPort --strictPort *> '$RunDir\web.log'"
}
$wv = Start-Process pwsh -ArgumentList '-NoProfile', '-Command', $npmScript -PassThru -WindowStyle Hidden
$wv.Id | Set-Content (Join-Path $RunDir 'web.pid')

# ★ vite 只监听 ::1，必须用 localhost 访问（127.0.0.1 会"拒绝连接"）
if (-not (Wait-Http "http://localhost:$WebPort/" '前端' 60)) { exit 1 }

# ---------------------------------------------------------------- 冒烟：真的登录一次
Write-Host '== 冒烟：登录 + 提问 ==' -ForegroundColor Cyan
try {
    $login = Invoke-RestMethod -Uri "http://localhost:$WebPort/api/auth/login" -Method Post `
        -ContentType 'application/json' `
        -Body (@{ username = 'hr_demo'; password = 'policy123' } | ConvertTo-Json) -TimeoutSec 30
    Write-Host "  [ok] 登录成功（roles=$($login.roles -join ',')）" -ForegroundColor Green
} catch {
    Write-Host "  [x] 登录失败：$($_.Exception.Message)" -ForegroundColor Red
    Write-Host "      看日志：$RunDir\java.log" -ForegroundColor Yellow
    exit 1
}

Write-Host ''
Write-Host "打开：http://localhost:$WebPort" -ForegroundColor Cyan
Write-Host "账号：hr_demo / emp_demo    口令：policy123" -ForegroundColor Cyan
Write-Host '关闭：.\stop-dev.ps1' -ForegroundColor DarkGray
