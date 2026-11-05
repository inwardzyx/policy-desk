<#
.SYNOPSIS
    为演示录像准备一个干净的语料分区（幂等：重复跑会先删掉同名文档再传）。

.DESCRIPTION
    为什么要单独准备：
      录像里最值钱的一条是"同一个问题，hr 看得到、employee 看不到"。
      这需要**同一分区里同时存在 public 与 hr 两种 ACL 的文档**，
      而库里现成的语料（company / default）不带这种对比。

    分区固定叫 `demo`，两篇文档：
      · 员工手册（acl=public）—— 两个角色都能看到
      · 薪酬制度（acl=hr）    —— 只有 hr 能看到

.PARAMETER BaseUrl
    默认 http://localhost:8088（compose 里的 nginx）。

.PARAMETER User / Password
    需要 hr 角色才能上传。

.EXAMPLE
    .\scripts\demo-seed.ps1
#>
param(
    [string]$BaseUrl = 'http://localhost:8088',
    [string]$User = 'hr_demo',
    [string]$Password = 'policy123',
    [string]$Namespace = 'demo'
)

$ErrorActionPreference = 'Stop'

function New-TempMd($name, $content) {
    $p = Join-Path $env:TEMP $name
    [IO.File]::WriteAllText($p, $content, [Text.UTF8Encoding]::new($false))
    return $p
}

Write-Host "== 登录 $BaseUrl ==" -ForegroundColor Cyan
$login = Invoke-RestMethod -Uri "$BaseUrl/api/auth/login" -Method Post -ContentType 'application/json' `
    -Body (@{ username = $User; password = $Password } | ConvertTo-Json) -TimeoutSec 60
$h = @{ Authorization = "Bearer $($login.token)" }
Write-Host "  ok（roles=$($login.roles -join ',')）" -ForegroundColor Green

Write-Host "== 清掉上一次的 demo 文档（幂等）==" -ForegroundColor Cyan
try {
    $existing = Invoke-RestMethod -Uri "$BaseUrl/api/docs?namespace=$Namespace" -Headers $h -TimeoutSec 60
    foreach ($d in $existing.documents) {
        $null = Invoke-RestMethod -Uri "$BaseUrl/api/docs/$Namespace/$($d.doc_id)" -Method Delete -Headers $h -TimeoutSec 60
        Write-Host "  删除 $($d.doc_id)" -ForegroundColor DarkGray
    }
} catch { Write-Host '  （分区还不存在，跳过）' -ForegroundColor DarkGray }

Write-Host "== 上传两篇 ==" -ForegroundColor Cyan
$public = New-TempMd 'demo-annual-leave.md' @"
## 年假

入职满一年的员工每年享有 5 天带薪年假，满三年为 10 天，满十年为 15 天。
年假需提前三个工作日申请，经直属主管批准后生效。
"@
$hrOnly = New-TempMd 'demo-overtime.md' @"
## 加班与补贴

工作日加班按工资的 1.5 倍计发，休息日加班按 2 倍计发，法定节假日按 3 倍计发。
一线城市出差住宿补贴上限为每晚 800 元，凭发票报销。
"@

$r1 = Invoke-RestMethod -Uri "$BaseUrl/api/docs" -Method Post -Headers $h `
    -Form @{ file = Get-Item $public; namespace = $Namespace; acl = 'public' } -TimeoutSec 120
Write-Host "  public：$($r1.document.doc_id)（$($r1.namespace_chunks) 块）" -ForegroundColor Green

$r2 = Invoke-RestMethod -Uri "$BaseUrl/api/docs" -Method Post -Headers $h `
    -Form @{ file = Get-Item $hrOnly; namespace = $Namespace; acl = 'hr' } -TimeoutSec 120
Write-Host "  hr    ：$($r2.document.doc_id)（$($r2.namespace_chunks) 块）" -ForegroundColor Green

Write-Host ''
Write-Host '== 录像时用这三句（都指向 demo 分区）==' -ForegroundColor Cyan
Write-Host '  hr  : 「加班费怎么算？」        → 应命中「加班与补贴」'
Write-Host '  hr  : 「年假有几天？」          → 应命中「年假」'
Write-Host '  emp : 「加班费怎么算？」        → 应拒答（acl=hr 的文档拿不到）'
Write-Host ''
Write-Host '★ 上传完后建议等 2-3 秒再问：Python 侧要热更索引（reload_enterprise）。' -ForegroundColor Yellow
