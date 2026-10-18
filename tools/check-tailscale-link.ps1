<#
.SYNOPSIS
    检查 DSH 手机远程链路（Tailscale Serve -> dsh 3080）是否可用，并指出坏在哪、下一步做什么。

.DESCRIPTION
    逐项检查：Serve 转发目标端口、3080 监听、免令牌网关插件登记、受信域名配置、
    真实域名首页与 API 响应、本机裸 Host 对照（安全边界）。
    退出码 0 表示可用；1 表示有故障，具体原因写在输出的结论段。

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File "<repo>\tools\check-tailscale-link.ps1"
#>
[CmdletBinding()]
param()

$ErrorActionPreference = 'Continue'
$repoRoot    = Split-Path $PSScriptRoot -Parent
$dshHome     = if ($env:DSH_HOME) { $env:DSH_HOME } else { Join-Path $env:USERPROFILE '.dsh' }
$profileJson = Join-Path $dshHome 'profiles\web\package.json'
$patchFile   = Join-Path $dshHome 'profiles\web\cordis.patch.yml'

$problems  = New-Object System.Collections.ArrayList
$nextSteps = New-Object System.Collections.ArrayList

function Write-Item([string]$label, [bool]$ok, [string]$detail) {
    $mark = if ($ok) { 'OK  ' } else { 'FAIL' }
    $color = if ($ok) { 'Green' } else { 'Red' }
    Write-Host ("[{0}] {1,-22} {2}" -f $mark, $label, $detail) -ForegroundColor $color
}

function Test-PortListening([int]$port) {
    $conn = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue
    return ($null -ne $conn -and @($conn).Count -gt 0)
}

function Invoke-CurlCode([string]$url, [string]$method, [string]$body) {
    $curlArgs = @('-s', '-o', 'NUL', '-w', '%{http_code}', '--max-time', '20')
    if ($method -eq 'POST') {
        $curlArgs += @('-X', 'POST', '-H', 'content-type: application/json', '-d', $body)
    }
    $curlArgs += $url
    $raw = & curl.exe @curlArgs 2>$null
    $code = 0
    [void][int]::TryParse(([string]$raw).Trim(), [ref]$code)
    return $code
}

Write-Host '=== DSH 手机远程链路自检 ===' -ForegroundColor Cyan
Write-Host "dsh 用户目录: $dshHome"
Write-Host ''

# ---- 1. Tailscale 与 Serve 转发目标 ----
$serveText = ''
$serveExit = 1
try {
    $serveText = (& tailscale serve status 2>&1 | Out-String)
    $serveExit = $LASTEXITCODE
} catch {
    $serveText = ''
}

$domain = ''
$domainMatch = [regex]::Match($serveText, 'https://([A-Za-z0-9][A-Za-z0-9.\-]+)')
if ($domainMatch.Success) { $domain = $domainMatch.Groups[1].Value }

$proxyPort = 0
$portMatch = [regex]::Match($serveText, 'proxy\s+https?://127\.0\.0\.1:(\d+)')
if ($portMatch.Success) { $proxyPort = [int]$portMatch.Groups[1].Value }

if ($domain -eq '') {
    Write-Item 'Tailscale Serve 发布' $false '没有读到发布地址（Serve 未开启或 Tailscale 未登录）'
    [void]$problems.Add('Tailscale Serve 没有对外发布任何地址')
    [void]$nextSteps.Add('执行 tailscale serve --bg --https=443 http://127.0.0.1:3081')
} else {
    Write-Item 'Tailscale Serve 发布' $true $domain
    if ($proxyPort -eq 0) {
        Write-Item 'Serve 转发目标端口' $false '没有解析到转发端口'
        [void]$problems.Add('Serve 已发布，但读不出转发到哪个端口')
        [void]$nextSteps.Add('执行 tailscale serve --bg --https=443 http://127.0.0.1:3081')
    } elseif (Test-PortListening $proxyPort) {
        $forwardForm = switch ($proxyPort) {
            3080 { 'dsh 本体，认证链放行形态' }
            3081 { '免令牌网关反代形态' }
            default { '自定义端口' }
        }
        Write-Item 'Serve 转发目标端口' $true "127.0.0.1:$proxyPort → $forwardForm"
    } else {
        Write-Item 'Serve 转发目标端口' $false "127.0.0.1:$proxyPort 没有任何程序监听（手机侧表现是 502）"
        [void]$problems.Add("Serve 转发到 127.0.0.1:$proxyPort，但该端口没有程序监听")
        if ($proxyPort -eq 3081) {
            [void]$nextSteps.Add('3081 是免令牌网关端口：先看上面的插件检查项是否通过，然后重启 dsh')
        } else {
            [void]$nextSteps.Add('把 Serve 指回有程序监听的端口：tailscale serve --bg --https=443 http://127.0.0.1:3081')
        }
    }
}

# ---- 2. dsh 是否在监听 3080 ----
$dshListening = Test-PortListening 3080
if ($dshListening) {
    $pid3080 = (@(Get-NetTCPConnection -LocalPort 3080 -State Listen -ErrorAction SilentlyContinue) | Select-Object -First 1).OwningProcess
    Write-Item 'dsh 监听 3080' $true "进程号 $pid3080"
} else {
    Write-Item 'dsh 监听 3080' $false '端口没有监听'
    [void]$problems.Add('3080 端口没有程序监听，dsh 本体没起来')
    [void]$nextSteps.Add('在电脑上执行一次 dsh web')
}

# ---- 3. 免令牌网关插件登记 ----
$bundles = @()
if (Test-Path $profileJson) {
    try {
        $profileObj = Get-Content $profileJson -Raw -Encoding UTF8 | ConvertFrom-Json
        $bundles = @($profileObj.dsh.profile.bundles)
    } catch {
        $bundles = @()
    }
}

if ($bundles -contains 'dsh-auth-gateway') {
    Write-Item '免令牌网关插件' $true 'dsh-auth-gateway 已登记'
} else {
    Write-Item '免令牌网关插件' $false 'bundles 里没有 dsh-auth-gateway'
    [void]$problems.Add('免令牌网关插件丢失（手机用裸地址访问会得到 401）')
    [void]$nextSteps.Add("执行 powershell -ExecutionPolicy Bypass -File `"$repoRoot\tools\install-auth-gateway.ps1`"")
}

if ($bundles -contains 'dsh-file-upload') {
    Write-Item '插件冲突复查' $false 'bundles 里有 dsh-file-upload（会导致 dsh 启动即崩）'
    [void]$problems.Add('bundles 里混进了 dsh-file-upload，下次重启 dsh 会因同名冲突起不来')
    [void]$nextSteps.Add('从 profiles\web\package.json 的 dsh.profile.bundles 移除 dsh-file-upload，再重启 dsh')
} else {
    Write-Item '插件冲突复查' $true '没有 dsh-file-upload 冲突项'
}

# ---- 4. 受信域名配置 ----
$patchText = ''
if (Test-Path $patchFile) {
    try { $patchText = Get-Content $patchFile -Raw -Encoding UTF8 } catch { $patchText = '' }
}

if ($domain -ne '' -and $patchText -match [regex]::Escape($domain)) {
    Write-Item '受信域名配置' $true "cordis.patch.yml 已包含 $domain"
} elseif ($domain -ne '') {
    Write-Item '受信域名配置' $false "cordis.patch.yml 里没有 $domain"
    [void]$problems.Add("dsh 不认识受信域名 $domain（手机侧表现通常是 403）")
    [void]$nextSteps.Add("在 $patchFile 增加 - id: connection / config.trustedHosts: [$domain]，然后重启 dsh")
} else {
    Write-Item '受信域名配置' $false 'Serve 没有域名，跳过'
}

# ---- 5. 真实链路探测 ----
if ($domain -ne '') {
    $homeCode = Invoke-CurlCode "https://$domain/" 'GET' ''
    if ($homeCode -eq 200) {
        Write-Item '域名首页' $true 'HTTP 200'
    } else {
        Write-Item '域名首页' $false "HTTP $homeCode"
        if ($homeCode -eq 502) {
            [void]$problems.Add('域名首页回 502：Serve 转发目标端口没人监听')
        } elseif ($homeCode -eq 401) {
            [void]$problems.Add('域名首页回 401：认证没有放行（插件未生效或 Cookie 过期）')
        } elseif ($homeCode -eq 403) {
            [void]$problems.Add('域名首页回 403：Host 头不被信任')
        } else {
            [void]$problems.Add("域名首页回 HTTP $homeCode")
        }
    }

    $apiCode = Invoke-CurlCode "https://$domain/api/session/list" 'POST' '{}'
    if ($apiCode -eq 0) {
        Write-Item '域名 API 认证' $false '请求没有拿到响应'
        [void]$problems.Add('经域名的 API 请求没有响应')
    } elseif ($apiCode -eq 401 -or $apiCode -eq 403) {
        Write-Item '域名 API 认证' $false "HTTP $apiCode（认证或信任被拒）"
        [void]$problems.Add("经域名的 API 被拒（HTTP $apiCode）")
    } else {
        Write-Item '域名 API 认证' $true "HTTP $apiCode（非 401/403，认证已放行）"
    }
}

# ---- 6. 安全边界对照：本机裸 Host 必须是 401 ----
$localCode = Invoke-CurlCode 'http://127.0.0.1:3080/' 'GET' ''
if ($localCode -eq 401) {
    Write-Item '本机裸 Host 对照' $true 'HTTP 401（放行范围未扩大到本机）'
} elseif ($localCode -eq 0) {
    Write-Item '本机裸 Host 对照' $false '本机 3080 无响应'
} else {
    Write-Item '本机裸 Host 对照' $true "HTTP $localCode（非 401，注意确认是否符合预期）"
}

# ---- 结论 ----
Write-Host ''
Write-Host '=== 结论 ===' -ForegroundColor Cyan
if ($problems.Count -eq 0) {
    Write-Host '能不能用：能用。服务端链路（Tailscale -> Serve -> dsh）全部通过。' -ForegroundColor Green
    Write-Host '哪里坏了：没有发现服务端故障。'
    Write-Host '下一步：手机若仍连不上，检查手机 Tailscale 开关是否打开、登录账号是否与电脑同一个（tailscale status 看手机那行是否 offline）。'
    exit 0
}

Write-Host '能不能用：不能，服务端有故障。' -ForegroundColor Red
Write-Host '哪里坏了：'
foreach ($p in $problems) { Write-Host "  - $p" -ForegroundColor Yellow }
Write-Host '下一步：'
$index = 1
foreach ($s in $nextSteps) {
    Write-Host ("  {0}. {1}" -f $index, $s)
    $index++
}
Write-Host ''
Write-Host '详细步骤见 docs\Tailscale远程访问DSH-安装使用文档.md 第七节「远程链路故障恢复手册」。'
exit 1
