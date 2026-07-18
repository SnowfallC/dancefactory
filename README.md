# DanceFactory / 舞形工坊

DanceFactory 是一个支持 Windows 与 Android 的舞台队形编排工具。当前版本为 **1.1.5**。

## 构建环境

- Windows 10/11 x64
- JDK 21（Android 也可使用 JDK 17）
- Android 端需要 Android SDK 36

仓库已包含 Maven Wrapper 和 Gradle Wrapper，不需要单独安装 Maven 或 Gradle；首次构建会自动下载依赖。

## 获取源码

```powershell
git clone https://github.com/SnowfallC/dancefactory.git
cd dancefactory
```

## 构建 Windows 桌面端

运行测试并生成 JAR：

```powershell
.\mvnw.cmd test
.\mvnw.cmd package -DskipTests
```

JAR 输出到 `target/formation-tool-desktop-1.1.5.jar`。

生成包含运行时的 Windows 程序和分发 ZIP：

```powershell
.\build-exe.bat
```

ZIP 输出到 `dist/windows/dancefactory-windows-1.1.5.zip`。

## 构建 Android 端

配置 `ANDROID_SDK_ROOT` 后运行：

```powershell
.\gradlew.bat :app:lintDebug :app:assembleDebug
.\gradlew.bat :app:assembleRelease
```

- Debug APK：`app/build/outputs/apk/debug/app-debug.apk`
- Release APK：`app/build/outputs/apk/release/`

未提供本地签名配置时，Release APK 为未签名产物。官方签名与发布由项目维护者单独处理。

## 许可证

本项目使用 [GPL-3.0-or-later](LICENSE) 许可证。第三方依赖说明见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
