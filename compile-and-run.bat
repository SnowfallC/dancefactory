@echo off
setlocal EnableExtensions

set "PROJECT_DIR=%~dp0"
set "RUN_MODE=%~1"

echo ================================
echo   DanceFactory Desktop
echo ================================
echo.

if not defined JAVA_HOME (
    if exist "C:\Program Files\Android\Android Studio\jbr\bin\java.exe" (
        set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
    )
)

if not exist "%JAVA_HOME%\bin\java.exe" (
    echo [ERROR] JDK not found.
    echo Install JDK 17+ or set JAVA_HOME, then try again.
    pause
    exit /b 1
)

set "PATH=%JAVA_HOME%\bin;%PATH%"

echo [CHECK] Java
"%JAVA_HOME%\bin\java.exe" -version
if errorlevel 1 (
    echo [ERROR] Java failed.
    pause
    exit /b 1
)

echo.
echo [CHECK] Maven
set "MAVEN_CMD=%PROJECT_DIR%mvnw.cmd"
if not exist "%MAVEN_CMD%" (
    echo [ERROR] Maven Wrapper not found.
    pause
    exit /b 1
)

call "%MAVEN_CMD%" -version
if errorlevel 1 (
    echo [ERROR] Maven failed.
    pause
    exit /b 1
)

echo.
echo [1/3] Clean
call "%MAVEN_CMD%" clean
if errorlevel 1 (
    echo [ERROR] Clean failed.
    pause
    exit /b 1
)

echo.
echo [2/3] Compile
call "%MAVEN_CMD%" compile
if errorlevel 1 (
    echo [ERROR] Compile failed.
    pause
    exit /b 1
)

if /I "%RUN_MODE%"=="compile-only" (
    echo.
    echo Compile check passed.
    echo ================================
    pause
    exit /b 0
)

echo.
echo [3/3] Run
call "%MAVEN_CMD%" javafx:run
if errorlevel 1 (
    echo [ERROR] Run failed.
    pause
    exit /b 1
)
