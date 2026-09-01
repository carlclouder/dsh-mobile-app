# capture_screenshot.ps1 - verified screenshot (capture -> PNG check -> auto retry). ASCII-only.
# Usage: powershell -ExecutionPolicy Bypass -File tools\capture_screenshot.ps1 -Name session_list -OutDir <dir>
# NOTE: must use cmd /c byte redirection to save PNG. PowerShell `>` re-encodes binary to UTF-16 -> corrupt.
param(
    [Parameter(Mandatory=$true)][string]$Name,
    [Parameter(Mandatory=$true)][string]$OutDir,
    [int]$MaxRetry = 3
)
$adb = "D:\dshm\.toolchain\android-sdk\platform-tools\adb.exe"
New-Item -ItemType Directory $OutDir -Force | Out-Null
$target = Join-Path $OutDir "$Name.png"

function Test-Png([string]$Path) {
    if (-not (Test-Path $Path)) { return $false }
    try {
        $fs = [System.IO.File]::OpenRead($Path)
        $b = New-Object byte[] 8
        [void]$fs.Read($b, 0, 8)
        $fs.Close()
        # PNG magic: 89 50 4E 47 0D 0A 1A 0A
        return ($b[0] -eq 0x89 -and $b[1] -eq 0x50 -and $b[2] -eq 0x4E -and $b[3] -eq 0x47)
    } catch { return $false }
}

for ($i = 1; $i -le $MaxRetry; $i++) {
    if (Test-Path $target) { Remove-Item $target -Force }
    cmd /c "`"$adb`" exec-out screencap -p > `"$target`""
    if (Test-Png $target) {
        $sz = (Get-Item $target).Length
        Write-Host "OK   ${Name}.png (${sz} B) PNG verified (attempt $i)"
        exit 0
    }
    Write-Host "RETRY screenshot $Name verification failed, retry $i/$MaxRetry"
    Start-Sleep -Milliseconds 600
}
Write-Host "FAIL screenshot $Name not a valid PNG after $MaxRetry attempts"
exit 1
