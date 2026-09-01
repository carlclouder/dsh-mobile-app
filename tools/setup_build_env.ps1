# setup_build_env.ps1 - DSH Mobile build toolchain installer (idempotent, ASCII-only)
# Installs everything under <project>\.toolchain (no system pollution).
# Writes machine-readable state to .toolchain\setup_state.json after every phase
# so an interrupted run can resume exactly where it stopped.
# Usage: powershell -ExecutionPolicy Bypass -File tools\setup_build_env.ps1
#
# v4 changelog:
#   - curl uses -sS (silent): v3 died because curl's stderr progress meter was
#     promoted to a terminating error by WinPS 5.1 under $ErrorActionPreference=Stop.
#   - All native invocations wrapped with EAP=Continue (Invoke-Native).
#   - setup_state.json progress recording for checkpoint resume.
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$tc = Join-Path $root ".toolchain"
New-Item -ItemType Directory -Path $tc -Force | Out-Null
$statePath = Join-Path $tc "setup_state.json"

# ---------- state helpers ----------
function Load-State {
    if (Test-Path $statePath) {
        try {
            # ConvertFrom-Json yields a PSObject which cannot be indexed like a
            # hashtable; expand its properties into a real hashtable so that
            # Set-State and ContainsKey work across resume runs (v5 fix:
            # resume crashed at first Set-State when the state file existed).
            $obj = Get-Content $statePath -Raw | ConvertFrom-Json
            $ht = @{}
            if ($null -ne $obj) {
                $obj.PSObject.Properties | ForEach-Object { $ht[$_.Name] = $_.Value }
            }
            return $ht
        } catch { return @{} }
    }
    return $null
}
function Save-State($hashtable) {
    $hashtable["updated"] = (Get-Date -Format "yyyy-MM-ddTHH:mm:ss")
    $hashtable | ConvertTo-Json | Set-Content -Path $statePath -Encoding ASCII
    Write-Host "[state] saved: $($hashtable | ConvertTo-Json -Compress)"
}
$state = Load-State
if ($null -eq $state) { $state = @{} }

function Set-State($key, $value) {
    $script:state[$key] = $value
    Save-State $script:state
}
function Step($msg) { Write-Host "[setup] $msg" }

function Invoke-Native($exe, $argList) {
    # Run a native command with EAP=Continue so stderr never terminates the script.
    $prev = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try { & $exe @argList 2>&1 } finally { $ErrorActionPreference = $prev }
}

function Download-WithFallback($name, $dest, $urls) {
    if (Test-Path $dest) { Step "$name already exists, skip download"; return $true }
    foreach ($url in $urls) {
        Step "Downloading $name from $url"
        Invoke-Native curl.exe @("-sS", "-L", "--connect-timeout", "20", "--retry", "2", "-o", "$dest.part", $url) | Out-Null
        if ($LASTEXITCODE -eq 0 -and (Test-Path "$dest.part") -and ((Get-Item "$dest.part").Length -gt 1MB)) {
            Move-Item "$dest.part" $dest -Force
            Step "$name downloaded ($([math]::Round((Get-Item $dest).Length/1MB,1)) MB)"
            return $true
        }
        Step "$name failed from this source, trying next"
        if (Test-Path "$dest.part") { Remove-Item "$dest.part" -Force }
    }
    return $false
}

# ---------- 1. JDK 17 (Temurin zip) ----------
Set-State "jdk" "running"
$jdkDir = Join-Path $tc "jdk17"
if (Test-Path (Join-Path $jdkDir "bin\java.exe")) { Step "JDK17 already installed, skip"; Set-State "jdk" "done" }
else {
    $jdkZip = Join-Path $tc "jdk17.zip"
    $ok = Download-WithFallback "JDK17" $jdkZip @(
        "https://mirrors.tuna.tsinghua.edu.cn/Adoptium/17/jdk/x64/windows/OpenJDK17U-jdk_x64_windows_hotspot_17.0.20_8.zip",
        "https://api.adoptium.net/v3/binary/latest/17/ga/windows/x64/jdk/hotspot/normal/eclipse"
    )
    if (-not $ok) { Set-State "jdk" "failed-download"; throw "JDK17 download failed (all sources)" }
    Set-State "jdk" "downloaded"
    Step "Extracting JDK17..."
    $tmp = Join-Path $tc "_jdk_extract"
    if (Test-Path $tmp) { Remove-Item $tmp -Recurse -Force }
    Expand-Archive -Path $jdkZip -DestinationPath $tmp -Force
    $inner = (Get-ChildItem $tmp -Directory | Select-Object -First 1).FullName
    if (Test-Path $jdkDir) { Remove-Item $jdkDir -Recurse -Force }
    Move-Item $inner $jdkDir
    Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue
    Remove-Item $jdkZip -Force -ErrorAction SilentlyContinue
    $ver = (Invoke-Native (Join-Path $jdkDir "bin\java.exe") @("-version")) | Select-Object -First 1
    Step "JDK17 ready: $ver"
    Set-State "jdk" "done"
}

# ---------- 2. Android SDK ----------
$forceSdk = $false
if ($state.ContainsKey("sdk") -and $state.sdk -eq "done") { $sdkPhaseSkip = $true } else { $sdkPhaseSkip = $false }
$sdkDir = Join-Path $tc "android-sdk"
New-Item -ItemType Directory -Path $sdkDir -Force | Out-Null
$platformTools = Join-Path $sdkDir "platform-tools\adb.exe"
$androidJar = Join-Path $sdkDir "platforms\android-35\android.jar"
if ($sdkPhaseSkip -and (Test-Path $platformTools) -and (Test-Path $androidJar)) {
    Step "Android SDK already installed, skip"
} else {
    Set-State "sdk" "running"
    $cltZip = Join-Path $tc "commandlinetools.zip"
    $ok = Download-WithFallback "commandline-tools" $cltZip @(
        "https://mirrors.cloud.tencent.com/AndroidSDK/commandlinetools-win-11076708_latest.zip",
        "https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip"
    )
    if (-not $ok) { Set-State "sdk" "failed-download"; throw "commandline-tools download failed (all sources)" }
    Set-State "sdk" "clt-downloaded"
    Step "Extracting commandline-tools..."
    $cltLatest = Join-Path $sdkDir "cmdline-tools\latest"
    if (-not (Test-Path $cltLatest)) {
        $tmp = Join-Path $sdkDir "_clt_extract"
        if (Test-Path $tmp) { Remove-Item $tmp -Recurse -Force }
        Expand-Archive -Path $cltZip -DestinationPath $tmp -Force
        New-Item -ItemType Directory -Path (Split-Path $cltLatest) -Force | Out-Null
        Move-Item (Join-Path $tmp "cmdline-tools") $cltLatest
        Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue
    }
    Remove-Item $cltZip -Force -ErrorAction SilentlyContinue
    Set-State "sdk" "clt-ready"
    $sdkmanagerBat = Join-Path $cltLatest "bin\sdkmanager.bat"

    Step "Accepting SDK licenses..."
    $oldJavaHome = $env:JAVA_HOME
    $env:JAVA_HOME = $jdkDir
    $prevEap = $ErrorActionPreference
    try {
        $yes = "y`r`n" * 10
        $ErrorActionPreference = "Continue"
        try { $yes | & cmd /c "`"$sdkmanagerBat`" --licenses" | Out-Null } finally { $ErrorActionPreference = $prevEap }
        Set-State "sdk" "licenses-accepted"
        $pkgs = @("platform-tools", "platforms;android-35", "build-tools;35.0.0")
        Step "Installing SDK packages (google source): $($pkgs -join ', ')"
        $ErrorActionPreference = "Continue"
        try { $out = & cmd /c "`"$sdkmanagerBat`" $pkgs" 2>&1 } finally { $ErrorActionPreference = $prevEap }
        $out | Select-Object -Last 2 | ForEach-Object { Step "  $($_.ToString())" }
        if (-not (Test-Path $androidJar)) {
            Step "Google source incomplete, trying Tencent mirror proxy..."
            $ErrorActionPreference = "Continue"
            try { $yes | & cmd /c "`"$sdkmanagerBat`" --no_https --proxy=http --proxy_host=mirrors.cloud.tencent.com --proxy_port=443 --proxy_suffix=/AndroidSDK --licenses" | Out-Null } finally { $ErrorActionPreference = $prevEap }
            $ErrorActionPreference = "Continue"
            try { $out = & cmd /c "`"$sdkmanagerBat`" --no_https --proxy=http --proxy_host=mirrors.cloud.tencent.com --proxy_port=443 --proxy_suffix=/AndroidSDK $pkgs" 2>&1 } finally { $ErrorActionPreference = $prevEap }
            $out | Select-Object -Last 2 | ForEach-Object { Step "  $($_.ToString())" }
        }
    } finally {
        $env:JAVA_HOME = $oldJavaHome
        $ErrorActionPreference = $prevEap
    }
    if (-not (Test-Path $androidJar)) { Set-State "sdk" "failed-packages"; throw "android-35 platform install failed" }
    Step "Android SDK ready (platform-tools + android-35 + build-tools 35.0.0)"
    Set-State "sdk" "done"
}

# ---------- 3. Gradle 8.9 ----------
$gradleDir = Join-Path $tc "gradle-8.9"
$gradleBat = Join-Path $gradleDir "bin\gradle.bat"
if (Test-Path $gradleBat) { Step "Gradle 8.9 already installed, skip"; Set-State "gradle" "done" }
else {
    Set-State "gradle" "running"
    $gradleZip = Join-Path $tc "gradle-8.9-bin.zip"
    $ok = Download-WithFallback "Gradle 8.9" $gradleZip @(
        "https://mirrors.cloud.tencent.com/gradle/gradle-8.9-bin.zip",
        "https://services.gradle.org/distributions/gradle-8.9-bin.zip"
    )
    if (-not $ok) { Set-State "gradle" "failed-download"; throw "Gradle download failed (all sources)" }
    Set-State "gradle" "downloaded"
    Step "Extracting Gradle..."
    Expand-Archive -Path $gradleZip -DestinationPath $tc -Force
    Remove-Item $gradleZip -Force -ErrorAction SilentlyContinue
    Step "Gradle ready"
    Set-State "gradle" "done"
}

Step "===== BUILD TOOLCHAIN READY ====="
Step "JDK:    $jdkDir"
Step "SDK:    $sdkDir"
Step "Gradle: $gradleDir"
Set-State "all" "done"
