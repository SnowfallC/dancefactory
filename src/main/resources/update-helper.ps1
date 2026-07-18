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
Start-Sleep -Milliseconds 500

$parent = Split-Path -Parent $targetPath
$leaf = Split-Path -Leaf $targetPath
$backupPath = Join-Path $parent ("$leaf.update-backup-" + [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds())

try {
    Move-Item -LiteralPath $targetPath -Destination $backupPath
    New-Item -ItemType Directory -Path $targetPath | Out-Null
    Copy-Item -Path (Join-Path $sourcePath '*') -Destination $targetPath -Recurse -Force

    $targetExe = Join-Path $targetPath $ExeName
    if (-not (Test-Path -LiteralPath $targetExe -PathType Leaf)) {
        throw 'Updated executable is missing after copy'
    }

    Start-Process -FilePath $targetExe -WorkingDirectory $targetPath
    Remove-Item -LiteralPath $backupPath -Recurse -Force
} catch {
    if (Test-Path -LiteralPath $targetPath) {
        Remove-Item -LiteralPath $targetPath -Recurse -Force
    }
    if (Test-Path -LiteralPath $backupPath) {
        Move-Item -LiteralPath $backupPath -Destination $targetPath
    }
    throw
}
