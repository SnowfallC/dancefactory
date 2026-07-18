[CmdletBinding()]
param(
    [int]$p0 = 0,
    [int]$p1 = 0,
    [int]$p2 = 0,
    [switch]$skipPackaging,
    [switch]$manualUiPass,
    [switch]$markSmokePass,
    [switch]$launchPackedExe
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

$envLogPath = Join-Path $reportDir "env-check.txt"
$testLogPath = Join-Path $reportDir "test-run.txt"
$summaryPath = Join-Path $reportDir "verification-summary.json"
$packagingResultPath = Join-Path $reportDir "packaging-result.json"
$smokeResultPath = Join-Path $reportDir "smoke-checklist.json"

function Write-LogLine {
    param(
        [string]$path,
        [string]$message
    )
    $line = "[{0}] {1}" -f (Get-Date).ToString("yyyy-MM-dd HH:mm:ss"), $message
    $line | Tee-Object -FilePath $path -Append | Out-Host
}

if (Test-Path $envLogPath) { Remove-Item -Path $envLogPath -Force }
if (Test-Path $testLogPath) { Remove-Item -Path $testLogPath -Force }

# 1) Environment check
$envStatus = [ordered]@{
    java = "FAIL"
    maven = "FAIL"
    jpackage = "FAIL"
    ffmpeg = "FAIL"
}

Write-LogLine -path $envLogPath -message "Start environment check"
try {
    $javaStdout = Join-Path $reportDir "java-version.stdout.log"
    $javaStderr = Join-Path $reportDir "java-version.stderr.log"
    $javaProc = Start-Process -FilePath "java" `
        -ArgumentList "-version" `
        -NoNewWindow `
        -Wait `
        -PassThru `
        -RedirectStandardOutput $javaStdout `
        -RedirectStandardError $javaStderr

    if (Test-Path $javaStdout) {
        Get-Content -Path $javaStdout -Encoding UTF8 | Tee-Object -FilePath $envLogPath -Append | Out-Host
    }
    if (Test-Path $javaStderr) {
        Get-Content -Path $javaStderr -Encoding UTF8 | Tee-Object -FilePath $envLogPath -Append | Out-Host
    }

    if ($javaProc.ExitCode -eq 0) {
        $envStatus.java = "PASS"
    } else {
        Write-LogLine -path $envLogPath -message "java -version exit code: $($javaProc.ExitCode)"
    }
} catch {
    Write-LogLine -path $envLogPath -message "java unavailable: $($_.Exception.Message)"
}

try {
    & (Join-Path $rootDir "mvnw.cmd") -version 2>&1 | Tee-Object -FilePath $envLogPath -Append | Out-Host
    if ($LASTEXITCODE -eq 0) { $envStatus.maven = "PASS" }
} catch {
    Write-LogLine -path $envLogPath -message "maven unavailable: $($_.Exception.Message)"
}

try {
    & where.exe jpackage 2>&1 | Tee-Object -FilePath $envLogPath -Append | Out-Host
    if ($LASTEXITCODE -eq 0) { $envStatus.jpackage = "PASS" }
} catch {
    Write-LogLine -path $envLogPath -message "jpackage check error: $($_.Exception.Message)"
}

try {
    & where.exe ffmpeg 2>&1 | Tee-Object -FilePath $envLogPath -Append | Out-Host
    if ($LASTEXITCODE -eq 0) { $envStatus.ffmpeg = "PASS" }
} catch {
    Write-LogLine -path $envLogPath -message "ffmpeg check error: $($_.Exception.Message)"
}

# 2) Build and automated tests
$buildStatus = "PENDING"
$autoTestStatus = "PENDING"
$buildDetails = @()
$autoTestDetails = @()
$runPackaging = -not $skipPackaging
$buildCommand = if ($runPackaging) { "mvnw.cmd package -DskipTests" } else { "mvnw.cmd test" }

Write-LogLine -path $testLogPath -message "Run $buildCommand"
Push-Location $rootDir
try {
    if ($runPackaging) {
        & (Join-Path $rootDir "mvnw.cmd") package -DskipTests 2>&1 | Tee-Object -FilePath $testLogPath -Append | Out-Host
    } else {
        & (Join-Path $rootDir "mvnw.cmd") test 2>&1 | Tee-Object -FilePath $testLogPath -Append | Out-Host
    }
    if ($LASTEXITCODE -eq 0) {
        $buildStatus = "PASS"
        $buildDetails += "$buildCommand passed"
    } else {
        $buildStatus = "FAIL"
        $buildDetails += "$buildCommand failed, exit code $LASTEXITCODE"
    }

    Write-LogLine -path $testLogPath -message "Run mvnw.cmd test"
    & (Join-Path $rootDir "mvnw.cmd") test 2>&1 | Tee-Object -FilePath $testLogPath -Append | Out-Host
    if ($LASTEXITCODE -eq 0) {
        $autoTestStatus = "PASS"
        $autoTestDetails += "mvnw.cmd test passed"
    } else {
        $autoTestStatus = "FAIL"
        $autoTestDetails += "mvnw.cmd test failed, exit code $LASTEXITCODE"
    }
} finally {
    Pop-Location
}

# 3) Smoke checklist
if ($markSmokePass) {
    & (Join-Path $PSScriptRoot "run-smoke.ps1") -markAllPass
} else {
    & (Join-Path $PSScriptRoot "run-smoke.ps1")
}

$smokeStatus = "PENDING"
$manualDetails = @()

if (Test-Path $smokeResultPath) {
    $smokeJson = Get-Content -Path $smokeResultPath -Raw -Encoding UTF8 | ConvertFrom-Json
    $smokeFailed = $smokeJson.items | Where-Object { $_.status -eq "FAIL" }
    $smokePending = $smokeJson.items | Where-Object { $_.status -eq "PENDING" }
    if ($smokeFailed.Count -gt 0) {
        $smokeStatus = "FAIL"
        $manualDetails += "Smoke checklist contains FAIL items"
    } elseif ($smokePending.Count -gt 0) {
        $smokeStatus = "PENDING"
        $manualDetails += "Smoke checklist contains PENDING items"
    } else {
        $smokeStatus = "PASS"
        $manualDetails += "Smoke checklist all PASS"
    }
}

if ($manualUiPass) {
    $manualDetails += "Manual UI verification confirmed for mvnw.cmd javafx:run"
    if ($smokeStatus -eq "PASS") {
        $manualStatus = "PASS"
    } elseif ($smokeStatus -eq "FAIL") {
        $manualStatus = "FAIL"
    } else {
        $manualStatus = "PENDING"
    }
} else {
    $manualStatus = "PENDING"
    $manualDetails += "Manual UI verification not confirmed, run mvnw.cmd javafx:run"
}

# 4) Packaging verification
$packagingStatus = "SKIPPED"
$packagingDetails = @()
if ($runPackaging) {
    $packagingArgs = @()
    if ($launchPackedExe) {
        $packagingArgs += "-launchExe"
    }

    & (Join-Path $PSScriptRoot "run-packaging-verify.ps1") @packagingArgs
    if (Test-Path $packagingResultPath) {
        $packagingResult = Get-Content -Path $packagingResultPath -Raw -Encoding UTF8 | ConvertFrom-Json
        $packagingStatus = $packagingResult.gate
        $packagingDetails += $packagingResult.details
    } else {
        $packagingStatus = "FAIL"
        $packagingDetails += "packaging-result.json not found"
    }
}

# 5) Summary
$envFail = $envStatus.Values -contains "FAIL"
$hasBlockingDefect = ($p0 -gt 0 -or $p1 -gt 0)
$statusList = @($buildStatus, $autoTestStatus, $manualStatus)
if ($runPackaging) { $statusList += $packagingStatus }

if ($hasBlockingDefect -or $statusList -contains "FAIL") {
    $gateResult = "FAIL"
} elseif ($envFail -or $statusList -contains "PENDING" -or $statusList -contains "SKIPPED") {
    $gateResult = "PENDING"
} else {
    $gateResult = "PASS"
}

$summary = [ordered]@{
    scope = "desktop-windows"
    qualityBar = "release"
    build = @{
        status = $buildStatus
        details = ($buildDetails -join "; ")
    }
    automatedTests = @{
        status = $autoTestStatus
        details = ($autoTestDetails -join "; ")
    }
    manualTests = @{
        status = $manualStatus
        details = ($manualDetails -join "; ")
    }
    packaging = @{
        status = $packagingStatus
        details = ($packagingDetails -join "; ")
    }
    defects = @{
        p0 = $p0
        p1 = $p1
        p2 = $p2
    }
    gateResult = $gateResult
    env = $envStatus
    updatedAt = (Get-Date).ToString("yyyy-MM-dd HH:mm:ss")
}

$summary | ConvertTo-Json -Depth 6 | Set-Content -Path $summaryPath -Encoding UTF8
Write-Host "Verification summary generated: $summaryPath"
Write-Host "Current gate result: $gateResult"

if ($gateResult -eq "FAIL") {
    exit 1
}
