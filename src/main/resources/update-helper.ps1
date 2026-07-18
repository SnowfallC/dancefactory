param(
    [Parameter(Mandatory = $true)][long]$ProcessId,
    [Parameter(Mandatory = $true)][string]$Source,
    [Parameter(Mandatory = $true)][string]$Target,
    [Parameter(Mandatory = $true)][string]$ExeName
)

$ErrorActionPreference = 'Stop'
$sourcePath = [System.IO.Path]::GetFullPath($Source)
$targetPath = [System.IO.Path]::GetFullPath($Target)
$sourceExe = Join-Path $sourcePath $ExeName

if (-not (Test-Path -LiteralPath $sourceExe -PathType Leaf)) {
    throw "Update package does not contain $ExeName"
}

Wait-Process -Id $ProcessId -ErrorAction SilentlyContinue

function Invoke-FileOperationWithRetry {
    param(
        [Parameter(Mandatory = $true)][scriptblock]$Operation,
        [Parameter(Mandatory = $true)][string]$Description
    )

    $lastError = $null
    for ($attempt = 1; $attempt -le 30; $attempt++) {
        try {
            & $Operation
            return
        } catch {
            $lastError = $_
            Start-Sleep -Seconds 1
        }
    }

    throw "$Description 在 30 秒内未完成: $($lastError.Exception.Message)"
}

$parent = Split-Path -Parent $targetPath
$leaf = Split-Path -Leaf $targetPath
$backupPath = Join-Path $parent ("$leaf.update-backup-" + [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds())
$backupCreated = $false

try {
    Invoke-FileOperationWithRetry -Description '备份旧版本' -Operation {
        Move-Item -LiteralPath $targetPath -Destination $backupPath -ErrorAction Stop
    }
    $backupCreated = $true
    New-Item -ItemType Directory -Path $targetPath | Out-Null
    Invoke-FileOperationWithRetry -Description '复制新版本' -Operation {
        Copy-Item -Path (Join-Path $sourcePath '*') -Destination $targetPath -Recurse -Force -ErrorAction Stop
    }

    $targetExe = Join-Path $targetPath $ExeName
    if (-not (Test-Path -LiteralPath $targetExe -PathType Leaf)) {
        throw 'Updated executable is missing after copy'
    }

    Start-Process -FilePath $targetExe -WorkingDirectory $targetPath
    Remove-Item -LiteralPath $backupPath -Recurse -Force -ErrorAction SilentlyContinue
} catch {
    if ($backupCreated) {
        if (Test-Path -LiteralPath $targetPath) {
            Remove-Item -LiteralPath $targetPath -Recurse -Force -ErrorAction SilentlyContinue
        }
        if (Test-Path -LiteralPath $backupPath) {
            Invoke-FileOperationWithRetry -Description '恢复旧版本' -Operation {
                Move-Item -LiteralPath $backupPath -Destination $targetPath -ErrorAction Stop
            }
        }
    }
    throw
}
