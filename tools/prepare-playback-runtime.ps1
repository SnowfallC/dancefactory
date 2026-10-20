param(
    [string]$DestinationDirectory = "",
    [string]$ProjectDirectory = (Join-Path $PSScriptRoot "..")
)

$ErrorActionPreference = "Stop"
$projectDirectory = [IO.Path]::GetFullPath($ProjectDirectory)
if ([string]::IsNullOrWhiteSpace($DestinationDirectory)) {
    $DestinationDirectory = Join-Path $projectDirectory "target/native-runtime"
}
$downloadDirectory = Join-Path $projectDirectory "target/native-download"
$destination = [IO.Path]::GetFullPath($DestinationDirectory)
$playerDirectory = Join-Path $destination "mpv"
$archivePath = Join-Path $downloadDirectory "mpv-dev-x86_64-20261020.7z"
$archiveUrl = "https://github.com/shinchiro/mpv-winbuild-cmake/releases/download/20261020/mpv-dev-x86_64-20261020-git-6a262d11f6.7z"
$archiveHash = "D16EA67BCC46D4EE9170F05B322D7184768F216F458BEF089C3397A6BEAFF83E"
$libraryHash = "F2E18B3D4226FE09D54970A9BA1EE89559445A614ADDC260AA7C911389734CA1"
[IO.Directory]::CreateDirectory($downloadDirectory) | Out-Null
[IO.Directory]::CreateDirectory($playerDirectory) | Out-Null

# 固定版本和哈希，禁止构建时静默升级播放内核。
if (-not (Test-Path -LiteralPath $archivePath)) {
    Invoke-WebRequest -UseBasicParsing -Uri $archiveUrl -OutFile $archivePath
}
if ((Get-FileHash -LiteralPath $archivePath -Algorithm SHA256).Hash -ne $archiveHash) {
    throw "播放组件压缩包校验失败，请检查 $archivePath"
}
& tar -xf $archivePath -C $playerDirectory "libmpv-2.dll"
if ($LASTEXITCODE -ne 0) { throw "无法解压播放组件，需要 Windows tar。" }
$libraryPath = Join-Path $playerDirectory "libmpv-2.dll"
if ((Get-FileHash -LiteralPath $libraryPath -Algorithm SHA256).Hash -ne $libraryHash) {
    throw "播放组件文件校验失败。"
}

$utf8 = New-Object System.Text.UTF8Encoding($false)
$licenses = @{
    "COPYRIGHT-mpv.txt" = "https://raw.githubusercontent.com/mpv-player/mpv/6a262d11f6/Copyright"
    "LICENSE-GPL-mpv.txt" = "https://raw.githubusercontent.com/mpv-player/mpv/6a262d11f6/LICENSE.GPL"
    "LICENSE-LGPL-mpv.txt" = "https://raw.githubusercontent.com/mpv-player/mpv/6a262d11f6/LICENSE.LGPL"
}
foreach ($entry in $licenses.GetEnumerator()) {
    $response = Invoke-WebRequest -UseBasicParsing -Uri $entry.Value
    [IO.File]::WriteAllText((Join-Path $playerDirectory $entry.Key), [string]$response.Content, $utf8)
}
$notice = @"
舞形工坊桌面播放组件

本目录使用 libmpv，由 mpv 项目开发。当前 Windows 构建按 GPL 分发。
Java 调用层使用 JNA，不使用 vlcj。舞形工坊自身按 GPL-3.0-or-later 发布。

内核：mpv v0.41.0-1224-g6a262d11f
构建：shinchiro/mpv-winbuild-cmake，20261020，通用 x86_64（非 x86_64-v3）
下载：$archiveUrl
压缩包 SHA256：$archiveHash
DLL SHA256：$libraryHash
上游源码：https://github.com/mpv-player/mpv/tree/6a262d11f6
构建脚本：https://github.com/shinchiro/mpv-winbuild-cmake
对应构建记录：https://github.com/shinchiro/mpv-winbuild-cmake/actions/runs/39344977314
FFmpeg 源码：https://github.com/FFmpeg/FFmpeg
JNA 源码及许可：https://github.com/java-native-access/jna/tree/5.17.0

用户不需要另外安装 mpv 或 VLC。运行时仅加载本应用目录里的播放组件。
FFmpeg 命令行组件继续用于兼容预览、音频提取和导出，详见其各自许可。
"@
[IO.File]::WriteAllText((Join-Path $playerDirectory "THIRD-PARTY-NOTICES.txt"), $notice, $utf8)
Write-Output "播放组件准备完成：$libraryPath"
