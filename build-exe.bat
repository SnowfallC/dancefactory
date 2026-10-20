@echo off
chcp 65001 > nul
setlocal EnableExtensions EnableDelayedExpansion

set "PROJECT_DIR=%~dp0"
set "APP_VERSION=unknown"

echo ========================================
echo 舞形工坊 - Windows 电脑端生成脚本
echo ========================================
echo.
echo 此脚本会生成独立 Windows 程序，并额外压缩为分发 zip。
echo.

REM 自动使用 Android Studio 自带 JDK，避免依赖全局 PATH
if not defined JAVA_HOME (
    for /f "delims=" %%J in ('powershell -NoProfile -ExecutionPolicy Bypass -Command "Get-ChildItem -Path '%PROJECT_DIR%.tools' -Filter jpackage.exe -Recurse -ErrorAction SilentlyContinue | Select-Object -First 1 -ExpandProperty DirectoryName" 2^>nul') do (
        for %%P in ("%%J\..") do set "JAVA_HOME=%%~fP"
    )
)

if not defined JAVA_HOME (
    if exist "C:\Program Files\Android\Android Studio\jbr\bin\java.exe" (
        set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
    )
)

if not exist "%JAVA_HOME%\bin\java.exe" (
    echo [错误] 未找到 JDK。
    echo 请安装 JDK 17+，或安装 Android Studio 后重试。
    pause
    exit /b 1
)

set "PATH=%JAVA_HOME%\bin;%PATH%"

echo [1/8] 检查 Java 环境...
"%JAVA_HOME%\bin\java.exe" -version
if %ERRORLEVEL% NEQ 0 (
    echo [错误] Java 无法运行。
    pause
    exit /b 1
)

echo.
echo [2/8] 检查 jpackage 工具...
if not exist "%JAVA_HOME%\bin\jpackage.exe" (
    echo [错误] 当前 JDK 不包含 jpackage。
    echo 请确认使用的是完整 JDK，而不是 JRE。
    pause
    exit /b 1
)

echo.
echo [3/8] 检查 Maven 环境...
set "MAVEN_CMD=%PROJECT_DIR%mvnw.cmd"
if not exist "%MAVEN_CMD%" (
    echo [错误] 未找到 Maven Wrapper。
    pause
    exit /b 1
)

call "%MAVEN_CMD%" -version
if %ERRORLEVEL% NEQ 0 (
    echo [错误] Maven 无法运行。
    pause
    exit /b 1
)

for /f "delims=" %%V in ('powershell -NoProfile -ExecutionPolicy Bypass -Command "$pom = [xml](Get-Content -LiteralPath '%PROJECT_DIR%pom.xml' -Raw); $pom.project.version"') do (
    set "APP_VERSION=%%V"
)

echo.
echo [4/8] 清理旧的构建文件...
call "%MAVEN_CMD%" clean
if %ERRORLEVEL% NEQ 0 (
    echo [错误] 清理失败。
    pause
    exit /b 1
)

echo.
echo [5/8] 编译并打包 JavaFX 应用...
powershell -NoProfile -ExecutionPolicy Bypass -Command "& ([ScriptBlock]::Create([IO.File]::ReadAllText((Join-Path $env:PROJECT_DIR 'tools/prepare-playback-runtime.ps1'),[Text.Encoding]::UTF8))) -ProjectDirectory $env:PROJECT_DIR"
if %ERRORLEVEL% NEQ 0 (
    echo [错误] 原生播放组件准备失败。
    pause
    exit /b 1
)
call "%MAVEN_CMD%" package -DskipTests
if %ERRORLEVEL% NEQ 0 (
    echo [错误] Maven 打包失败。
    pause
    exit /b 1
)

echo.
echo [6/8] 准备 jpackage 输入目录...
if exist "%PROJECT_DIR%jpackage-input" rmdir /s /q "%PROJECT_DIR%jpackage-input"
mkdir "%PROJECT_DIR%jpackage-input"
mkdir "%PROJECT_DIR%jpackage-input\libs"
mkdir "%PROJECT_DIR%jpackage-input\ffmpeg"

copy "%PROJECT_DIR%target\formation-tool-desktop-%APP_VERSION%.jar" "%PROJECT_DIR%jpackage-input\" > nul
xcopy "%PROJECT_DIR%target\libs" "%PROJECT_DIR%jpackage-input\libs\" /E /I /Y > nul
xcopy "%PROJECT_DIR%target\native-runtime" "%PROJECT_DIR%jpackage-input\native\" /E /I /Y > nul

set "FFMPEG_FOUND=0"
if exist "C:\ProgramData\chocolatey\lib\ffmpeg\tools\ffmpeg\bin\ffmpeg.exe" (
    copy "C:\ProgramData\chocolatey\lib\ffmpeg\tools\ffmpeg\bin\ffmpeg.exe" "%PROJECT_DIR%jpackage-input\ffmpeg\" > nul
    set "FFMPEG_FOUND=1"
)

if "%FFMPEG_FOUND%"=="0" (
    if exist "C:\ffmpeg\bin\ffmpeg.exe" (
        copy "C:\ffmpeg\bin\ffmpeg.exe" "%PROJECT_DIR%jpackage-input\ffmpeg\" > nul
        set "FFMPEG_FOUND=1"
    )
)

if "%FFMPEG_FOUND%"=="0" (
    for /f "delims=" %%F in ('where ffmpeg.exe 2^>nul') do (
        copy "%%F" "%PROJECT_DIR%jpackage-input\ffmpeg\" > nul
        set "FFMPEG_FOUND=1"
        goto :ffmpeg_done
    )
)

:ffmpeg_done
if "%FFMPEG_FOUND%"=="0" (
    echo [警告] 未找到 FFmpeg，视频导出功能可能不可用。
) else (
    echo FFmpeg 已复制到打包目录。
)

echo.
echo [7/8] 使用 jpackage 创建 Windows 应用...
if exist "%PROJECT_DIR%installer" rmdir /s /q "%PROJECT_DIR%installer"

"%JAVA_HOME%\bin\jpackage.exe" ^
    --type app-image ^
    --input "%PROJECT_DIR%jpackage-input" ^
    --name "舞形工坊" ^
    --main-jar "formation-tool-desktop-%APP_VERSION%.jar" ^
    --main-class "com.formationtool.desktop.MainApp" ^
    --dest "%PROJECT_DIR%installer" ^
    --app-version "%APP_VERSION%" ^
    --description "舞形工坊 - 队形编排工具" ^
    --vendor "舞形工坊" ^
    --copyright "Copyright 2024-2026 舞形工坊" ^
    --java-options "-Dfile.encoding=UTF-8" ^
    --java-options "--enable-native-access=ALL-UNNAMED" ^
    --icon "%PROJECT_DIR%src\main\resources\app-icon.ico" ^
    --module-path "%PROJECT_DIR%jpackage-input\libs" ^
    --add-modules "javafx.controls,javafx.fxml,javafx.media,javafx.swing,java.logging,jdk.crypto.ec,jdk.unsupported"

if %ERRORLEVEL% NEQ 0 (
    echo [错误] jpackage 打包失败。
    pause
    exit /b 1
)

if not exist "%PROJECT_DIR%installer\舞形工坊\舞形工坊.exe" (
    echo [错误] 未找到打包后的程序。
    pause
    exit /b 1
)

echo.
echo [8/8] 压缩分发包...
set "DIST_DIR=%PROJECT_DIR%dist\windows"
set "ZIP_PATH=%DIST_DIR%\dancefactory-windows-%APP_VERSION%.zip"
if not exist "%DIST_DIR%" mkdir "%DIST_DIR%"
python "%PROJECT_DIR%tools\package_windows_release.py" --source "%PROJECT_DIR%installer\舞形工坊" --output "%ZIP_PATH%" --version "%APP_VERSION%"
if %ERRORLEVEL% NEQ 0 (
    echo [警告] zip 压缩失败，但 exe 已经生成。
) else (
    echo zip 分发包：%ZIP_PATH%
)

echo.
echo ========================================
echo Windows 电脑端生成成功！
echo ========================================
echo.
echo 程序位置：%PROJECT_DIR%installer\舞形工坊\舞形工坊.exe
echo 分发包：%ZIP_PATH%
echo.
echo ========================================
pause
