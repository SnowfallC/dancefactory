[CmdletBinding()]
param(
    [switch]$markAllPass
)

[Console]::InputEncoding = [System.Text.UTF8Encoding]::new($false)
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
$OutputEncoding = [System.Text.UTF8Encoding]::new($false)

$reportDir = Join-Path $PSScriptRoot "reports"
New-Item -ItemType Directory -Path $reportDir -Force | Out-Null

$checkItems = @(
    @{ id = "project-list"; name = "Project list: create/open/delete/sort"; status = "PENDING"; notes = "" },
    @{ id = "dancer-crud"; name = "Dancer: add/edit/delete/style preview"; status = "PENDING"; notes = "" },
    @{ id = "formation-flow"; name = "Formation: add/delete/copy preset"; status = "PENDING"; notes = "" },
    @{ id = "timeline-flow"; name = "Timeline: drag/keyframe/F-flow/undo"; status = "PENDING"; notes = "" },
    @{ id = "media-flow"; name = "Media: import audio/video and playback sync"; status = "PENDING"; notes = "" },
    @{ id = "project-roundtrip"; name = "Project zip export/import roundtrip"; status = "PENDING"; notes = "" },
    @{ id = "export-flow"; name = "Export: single/split/flip/scale/reference"; status = "PENDING"; notes = "" },
    @{ id = "export-cancel"; name = "Export: cancel and retry"; status = "PENDING"; notes = "" },
    @{ id = "restart-recover"; name = "Restart: reopen and recover state"; status = "PENDING"; notes = "" }
)

if ($markAllPass) {
    foreach ($item in $checkItems) {
        $item.status = "PASS"
        $item.notes = "Marked by -markAllPass"
    }
}

$result = [ordered]@{
    scope = "desktop-smoke"
    generatedAt = (Get-Date).ToString("yyyy-MM-dd HH:mm:ss")
    markAllPass = [bool]$markAllPass
    items = $checkItems
}

$jsonPath = Join-Path $reportDir "smoke-checklist.json"
$mdPath = Join-Path $reportDir "smoke-checklist.md"

$result | ConvertTo-Json -Depth 5 | Set-Content -Path $jsonPath -Encoding UTF8

$lines = @(
    "# Desktop Smoke Checklist",
    "",
    "| ID | Scenario | Status | Notes |",
    "|---|---|---|---|"
)

foreach ($item in $checkItems) {
    $lines += "| $($item.id) | $($item.name) | $($item.status) | $($item.notes) |"
}

$lines | Set-Content -Path $mdPath -Encoding UTF8

Write-Host "Smoke checklist generated: $jsonPath"
Write-Host "Smoke markdown generated: $mdPath"
if (-not $markAllPass) {
    Write-Host "Current status is PENDING. Run manual smoke and update smoke-checklist.json."
}
