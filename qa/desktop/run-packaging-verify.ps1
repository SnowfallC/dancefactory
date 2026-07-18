[CmdletBinding()]
param(
    [switch]$launchExe
)

[Console]::InputEncoding = [System.Text.UTF8Encoding]::new($false)
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
$OutputEncoding = [System.Text.UTF8Encoding]::new($false)
if ($null -ne $PSVersionTable.PSVersion -and $PSVersionTable.PSVersion.Major -ge 7) {
    $PSNativeCommandUseErrorActionPreference = $false
}

$rootDir = Resolve-Path (Join-Path $PSScriptRoot "../..")
$reportDir = Join-Path $PSScriptRoot "reports"
New-Item -ItemType Directory -Path $reportDir -Force | Out-Null

$logPath = Join-Path $reportDir "packaging-check.txt"
$resultPath = Join-Path $reportDir "packaging-result.json"

function Write-Log {
    param([string]$message)
    $line = "[{0}] {1}" -f (Get-Date).ToString("yyyy-MM-dd HH:mm:ss"), $message
    $line | Tee-Object -FilePath $logPath -Append | Out-Host
}

if (Test-Path $logPath) {
    Remove-Item -Path $logPath -Force
}

$status = [ordered]@{
    packagingBuild = "PENDING"
    exeExists = "PENDING"
    ffmpegBundled = "PENDING"
    exeLaunch = "SKIPPED"
    details = @()
    gate = "PENDING"
    generatedAt = (Get-Date).ToString("yyyy-MM-dd HH:mm:ss")
}

Write-Log "Start packaging verification"
Write-Log "Project root: $rootDir"
$packageDestination = Join-Path $rootDir ("target/installer-verify-" + (Get-Date).ToString("yyyyMMddHHmmss"))
Write-Log "Package destination: $packageDestination"

Write-Log "Run: mvnw.cmd -DskipTests verify -Djpackage.destination=<dynamic>"
Push-Location $rootDir
try {
    & (Join-Path $rootDir "mvnw.cmd") -DskipTests verify "-Djpackage.destination=$packageDestination" 2>&1 | Tee-Object -FilePath $logPath -Append | Out-Host
    if ($LASTEXITCODE -eq 0) {
        $status.packagingBuild = "PASS"
        $status.details += "mvnw.cmd verify passed"
    } else {
        $status.packagingBuild = "FAIL"
        $status.details += "mvnw.cmd verify failed, exit code $LASTEXITCODE"
    }
} finally {
    Pop-Location
}

$exeSearchRoots = @(
    $packageDestination,
    (Join-Path $rootDir "target/installer"),
    (Join-Path $rootDir "installer")
)

$foundExe = $null
foreach ($exeRoot in $exeSearchRoots) {
    if (-not (Test-Path $exeRoot)) {
        continue
    }

    $candidate = Get-ChildItem -Path $exeRoot -Recurse -Filter "*.exe" -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -ne "ffmpeg.exe" } |
        Select-Object -First 1

    if ($null -ne $candidate) {
        $foundExe = $candidate.FullName
        break
    }
}

if ($null -ne $foundExe) {
    $status.exeExists = "PASS"
    $status.details += "Found packaged exe: $foundExe"
    Write-Log "Found packaged exe: $foundExe"
} else {
    $status.exeExists = "FAIL"
    $status.details += "Packaged exe not found"
    Write-Log "Packaged exe not found"
}

if ($null -ne $foundExe) {
    $exeDir = Split-Path $foundExe -Parent
    $ffmpegFile = Get-ChildItem -Path $exeDir -Recurse -Filter "ffmpeg.exe" -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($null -ne $ffmpegFile) {
        $status.ffmpegBundled = "PASS"
        $status.details += "Bundled ffmpeg found: $($ffmpegFile.FullName)"
        Write-Log "Bundled ffmpeg found: $($ffmpegFile.FullName)"
    } else {
        $status.ffmpegBundled = "FAIL"
        $status.details += "Bundled ffmpeg.exe not found in package"
        Write-Log "Bundled ffmpeg.exe not found in package"
    }
}

if ($launchExe -and $null -ne $foundExe) {
    Write-Log "Try to launch packaged exe for cold-start check"
    try {
        $proc = Start-Process -FilePath $foundExe -PassThru
        Start-Sleep -Seconds 15
        if ($proc.HasExited) {
            $status.exeLaunch = "FAIL"
            $status.details += "App exited in 15s, exit code $($proc.ExitCode)"
            Write-Log "App exited in 15s, exit code $($proc.ExitCode)"
        } else {
            $status.exeLaunch = "PASS"
            $status.details += "App launched and stayed alive for 15s"
            Write-Log "App launched and stayed alive for 15s, stop process"
            Stop-Process -Id $proc.Id -Force
        }
    } catch {
        $status.exeLaunch = "FAIL"
        $status.details += "App launch error: $($_.Exception.Message)"
        Write-Log "App launch error: $($_.Exception.Message)"
    }
}

$passItems = @($status.packagingBuild, $status.exeExists, $status.ffmpegBundled)
if ($launchExe) {
    $passItems += $status.exeLaunch
}

if ($passItems -contains "FAIL") {
    $status.gate = "FAIL"
} elseif ($passItems -contains "PENDING") {
    $status.gate = "PENDING"
} else {
    $status.gate = "PASS"
}

$status | ConvertTo-Json -Depth 5 | Set-Content -Path $resultPath -Encoding UTF8
Write-Log "Packaging result written to: $resultPath"

if ($status.gate -eq "FAIL") {
    exit 1
}
