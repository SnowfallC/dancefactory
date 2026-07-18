@echo off
chcp 65001 > nul
setlocal EnableExtensions EnableDelayedExpansion

set "PROJECT_DIR=%~dp0"
set "APP_VERSION=unknown"
set "BUILD_VARIANT=%~1"

if "%BUILD_VARIANT%"=="" set "BUILD_VARIANT=debug"

echo ========================================
echo 舞形工坊 - Android APK 生成脚本
echo ========================================
echo.

REM 自动使用 Android Studio 自带 JDK，避免依赖全局 PATH
if not defined JAVA_HOME (
    if exist "C:\Program Files\Android\Android Studio\jbr\bin\java.exe" (
        set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
    )
)

if not exist "%JAVA_HOME%\bin\java.exe" (
    echo [错误] 未找到 JDK。
    echo 请安装 Android Studio，或手动设置 JAVA_HOME 到 JDK 17+。
    pause
    exit /b 1
)

set "PATH=%JAVA_HOME%\bin;%PATH%"

REM 自动使用当前用户的 Android SDK
if not defined ANDROID_SDK_ROOT (
    if exist "%LOCALAPPDATA%\Android\Sdk" (
        set "ANDROID_SDK_ROOT=%LOCALAPPDATA%\Android\Sdk"
    )
)

if not defined ANDROID_HOME set "ANDROID_HOME=%ANDROID_SDK_ROOT%"

if not exist "%ANDROID_SDK_ROOT%\platforms" (
    echo [错误] 未找到 Android SDK。
    echo 请先在 Android Studio 中安装 Android SDK。
    pause
    exit /b 1
)

REM 写入本地 SDK 路径，Gradle 会读取这个文件
set "SDK_DIR=%ANDROID_SDK_ROOT:\=/%"
> "%PROJECT_DIR%local.properties" echo sdk.dir=%SDK_DIR%

REM 优先使用项目 Gradle Wrapper；如果没有，则使用本机已缓存的 Gradle
set "GRADLE_CMD="
if exist "%PROJECT_DIR%gradlew.bat" (
    set "GRADLE_CMD=%PROJECT_DIR%gradlew.bat"
) else (
    for /f "delims=" %%G in ('powershell -NoProfile -ExecutionPolicy Bypass -Command "Get-ChildItem -Path (Join-Path $env:USERPROFILE '.gradle\wrapper\dists') -Filter gradle.bat -Recurse -ErrorAction SilentlyContinue | Select-Object -First 1 -ExpandProperty FullName" 2^>nul') do (
        set "GRADLE_CMD=%%G"
    )
)

if not defined GRADLE_CMD (
    for /f "delims=" %%G in ('where gradle.bat 2^>nul') do (
        set "GRADLE_CMD=%%G"
        goto :gradle_found
    )
)

:gradle_found
if not defined GRADLE_CMD (
    echo [错误] 未找到 Gradle。
    echo 请用 Android Studio 打开项目同步一次，或安装 Gradle 后重试。
    pause
    exit /b 1
)

for /f "tokens=2 delims= " %%V in ('findstr /R /C:"versionName" "%PROJECT_DIR%app\build.gradle"') do (
    set "APP_VERSION=%%~V"
)
set "APP_VERSION=%APP_VERSION:"=%"

if /I "%BUILD_VARIANT%"=="release" (
    set "GRADLE_TASK=:app:assembleRelease"
    set "APK_SOURCE=%PROJECT_DIR%app\build\outputs\apk\release\app-release.apk"
    set "APK_FALLBACK_SOURCE=%PROJECT_DIR%app\build\outputs\apk\release\app-release-unsigned.apk"
    set "APK_NAME=dancefactory-android-%APP_VERSION%-release.apk"
) else (
    set "BUILD_VARIANT=debug"
    set "GRADLE_TASK=:app:assembleDebug"
    set "APK_SOURCE=%PROJECT_DIR%app\build\outputs\apk\debug\app-debug.apk"
    set "APK_NAME=dancefactory-android-%APP_VERSION%-debug.apk"
)

echo [环境] JAVA_HOME=%JAVA_HOME%
echo [环境] ANDROID_SDK_ROOT=%ANDROID_SDK_ROOT%
echo [环境] Gradle=%GRADLE_CMD%
echo [任务] %GRADLE_TASK%
echo.

call "%GRADLE_CMD%" %GRADLE_TASK% --no-daemon
if %ERRORLEVEL% NEQ 0 (
    echo.
    echo [错误] Android APK 生成失败。
    echo 如果提示依赖下载失败，请检查网络后重试。
    pause
    exit /b 1
)

if /I "%BUILD_VARIANT%"=="release" (
    if not exist "%APK_SOURCE%" (
        if exist "%APK_FALLBACK_SOURCE%" (
            set "APK_SOURCE=%APK_FALLBACK_SOURCE%"
            set "APK_NAME=dancefactory-android-%APP_VERSION%-release-unsigned.apk"
        )
    )
)

if not exist "%APK_SOURCE%" (
    echo [错误] 未找到 APK 输出文件：%APK_SOURCE%
    pause
    exit /b 1
)

set "DIST_DIR=%PROJECT_DIR%dist\android"
if not exist "%DIST_DIR%" mkdir "%DIST_DIR%"
copy /Y "%APK_SOURCE%" "%DIST_DIR%\%APK_NAME%" > nul

echo.
echo ========================================
echo Android APK 生成成功！
echo ========================================
echo.
echo 原始位置：%APK_SOURCE%
echo 分发位置：%DIST_DIR%\%APK_NAME%
echo.
if /I "%BUILD_VARIANT%"=="release" (
    if /I "%APK_NAME%"=="dancefactory-android-%APP_VERSION%-release-unsigned.apk" (
        echo 注意：未配置本地签名时，release APK 为未签名产物。
    ) else (
        echo 当前 release APK 已使用本地签名配置签名。
    )
)
echo ========================================
pause
