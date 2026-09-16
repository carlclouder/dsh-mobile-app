<#
.SYNOPSIS
    安装或修复 DSH 免令牌网关插件（dsh-auth-gateway）。

.DESCRIPTION
    幂等：已安装且版本正确时不做任何改动，只报告状态。
    用途：新机器部署、删除过 ~/.dsh、dsh 大版本升级后、或插件被误删时恢复。

    为什么需要手动跑一次：dsh 的 profile 清单记录的是"实际安装状态"而非"期望状态"，
    `dsh plugin install` 不会补齐被删除的插件（实测输出 Already up to date 且会清掉登记），
    因此恢复动作必须是重新 add 插件包。

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File "D:\AI任务\dsh-mobile-app\tools\install-auth-gateway.ps1"
#>
[CmdletBinding()]
param(
    # dsh 用户目录（默认取环境变量 DSH_HOME，未设置则用 ~/.dsh）
    [string]$DshHome = $(if ($env:DSH_HOME) { $env:DSH_HOME } else { Join-Path $env:USERPROFILE '.dsh' }),
    # dsh 命令位置（默认 npm 全局安装目录下的 dsh.cmd）
    [string]$DshCommand = (Join-Path $env:APPDATA 'npm\dsh.cmd'),
    # 插件包目录（默认用户目录下的 local-plugins）
    [string]$PluginStore = (Join-Path $(if ($env:DSH_HOME) { $env:DSH_HOME } else { Join-Path $env:USERPROFILE '.dsh' }) 'local-plugins')
)

$ErrorActionPreference = 'Continue'
$profileDir = Join-Path $DshHome 'profiles\web'
$profileJson = Join-Path $profileDir 'package.json'
$bundleName = 'dsh-auth-gateway'

Write-Host '=== DSH 免令牌网关插件：安装 / 修复 ===' -ForegroundColor Cyan
Write-Host "dsh 用户目录: $DshHome"
Write-Host "dsh 命令:     $DshCommand"

if (-not (Test-Path $DshCommand)) {
    Write-Host "[错误] 找不到 dsh 命令：$DshCommand" -ForegroundColor Red
    Write-Host '       请确认已通过 npm 全局安装 dsh，或用 -DshCommand 指定实际路径。'
    exit 1
}
if (-not (Test-Path $profileJson)) {
    Write-Host "[错误] 找不到 profile 清单：$profileJson" -ForegroundColor Red
    Write-Host '       该 profile 尚未创建：请先启动一次 dsh web 再运行本脚本。'
    exit 1
}

# ---- 选包：优先 local-plugins 里版本号最大的 tgz，其次仓库源码目录内刚打包的 tgz ----
$candidates = @()
if (Test-Path $PluginStore) {
    $candidates += Get-ChildItem $PluginStore -Filter "$bundleName-*.tgz" -ErrorAction SilentlyContinue
}
$repoDir = Join-Path $PSScriptRoot $bundleName
if (Test-Path $repoDir) {
    $candidates += Get-ChildItem $repoDir -Filter "$bundleName-*.tgz" -ErrorAction SilentlyContinue
}
if ($candidates.Count -eq 0) {
    Write-Host "[错误] 找不到插件包（$bundleName-*.tgz）" -ForegroundColor Red
    Write-Host "       已查找：$PluginStore 与 $repoDir"
    exit 1
}
$package = $candidates |
    Sort-Object @{ Expression = {
        $m = [regex]::Match($_.Name, '(\d+)\.(\d+)\.(\d+)')
        if ($m.Success) { [version]("{0}.{1}.{2}" -f $m.Groups[1].Value, $m.Groups[2].Value, $m.Groups[3].Value) } else { [version]'0.0.0' }
    } } |
    Select-Object -Last 1
$packageVersion = ([regex]::Match($package.Name, '(\d+\.\d+\.\d+)')).Groups[1].Value
Write-Host "插件包:       $($package.FullName)  (v$packageVersion)"

# ---- 当前状态 ----
$before = Get-Content $profileJson -Raw -Encoding UTF8 | ConvertFrom-Json
$registered = @($before.dsh.profile.bundles) -contains $bundleName
$installedDir = Join-Path $profileDir "node_modules\$bundleName"
$installedVersion = if (Test-Path (Join-Path $installedDir 'package.json')) {
    (Get-Content (Join-Path $installedDir 'package.json') -Raw -Encoding UTF8 | ConvertFrom-Json).version
} else { $null }
Write-Host "当前状态:     清单登记=$registered  已装版本=$(if ($installedVersion) { $installedVersion } else { '（无）' })"

$needInstall = (-not $registered) -or ($installedVersion -ne $packageVersion)
if (-not $needInstall) {
    Write-Host '[完成] 已是目标版本，无需改动。' -ForegroundColor Green
} else {
    Write-Host '开始安装（dsh plugin --profile web add）...' -ForegroundColor Yellow
    $env:DSH_HOME = $DshHome
    & $DshCommand plugin --profile web add $package.FullName 2>&1 | ForEach-Object { Write-Host "  $_" }
    if ($LASTEXITCODE -ne 0) {
        Write-Host "[错误] 安装命令退出码 $LASTEXITCODE" -ForegroundColor Red
        exit 1
    }
    Write-Host '[完成] 安装命令执行完毕。' -ForegroundColor Green
}

# ---- 冲突复查（dsh 0.1.5 起内置 file-upload；第三方同名会导致启动即崩） ----
$after = Get-Content $profileJson -Raw -Encoding UTF8 | ConvertFrom-Json
$bundles = @($after.dsh.profile.bundles)
if ($bundles -contains 'dsh-file-upload') {
    Write-Host '[修复] 检测到冲突插件 dsh-file-upload，正在移除（与 dsh 内置同名，会导致启动失败）...' -ForegroundColor Yellow
    $after.dsh.profile.bundles = @($bundles | Where-Object { $_ -ne 'dsh-file-upload' })
    if ($after.dependencies -and $after.dependencies.'dsh-file-upload') {
        $after.dependencies.PSObject.Properties.Remove('dsh-file-upload')
    }
    ($after | ConvertTo-Json -Depth 12) | Set-Content $profileJson -Encoding UTF8 -NoNewline
    Write-Host '[完成] 已移除冲突项。' -ForegroundColor Green
}

# ---- 结果与后续动作 ----
$final = Get-Content $profileJson -Raw -Encoding UTF8 | ConvertFrom-Json
$finalRegistered = @($final.dsh.profile.bundles) -contains $bundleName
Write-Host ''
Write-Host '=== 结果 ===' -ForegroundColor Cyan
Write-Host "清单登记: $finalRegistered"
Write-Host "插件目录: $(Test-Path $installedDir)"
Write-Host "bundles:  $((@($final.dsh.profile.bundles)) -join ', ')"
Write-Host ''
if ($finalRegistered) {
    Write-Host '下一步：重启 dsh 使其生效（bundles 清单变更不支持热加载）。' -ForegroundColor Yellow
    Write-Host '  重启后确认日志出现：[auth-gateway] 已开启 tailnet 免令牌放行'
    Write-Host '  验证命令：Get-NetTCPConnection -LocalPort 3080 -State Listen'
} else {
    Write-Host '[警告] 清单中仍未登记插件，请检查上面的安装输出。' -ForegroundColor Red
    exit 1
}
