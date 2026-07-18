# DanceFactory / 舞形工坊

DanceFactory 是一款面向舞团、偶像团体与舞台排练的队形编排工具，支持 **Windows 电脑版**和 **Android 手机版**。当前版本为 **1.1.5**。

- 图形化舞台与多舞者站位管理
- 音频波形、参考视频与关键帧时间轴
- F 键实时拍打队形变化区间
- 预览、3D 舞台、工程包与演示视频导出
- Windows 与 Android 工程协作

完整操作说明与高清演示素材请访问[舞形工坊官网项目](https://github.com/SnowfallC/Wuxinggongfang)，并从官网首页进入 Wiki。

## Windows 电脑版

![Windows 电脑版编辑器](docs/images/readme/windows-editor.png)

电脑版适合精确时间轴编辑、键盘快捷键和最终导出。编辑器由参考视频、中央舞台、舞者与关键帧列表、底部音频时间轴组成。

### 创建与管理舞者

![Windows 创建舞者](docs/images/readme/windows-add-dancer.png)

点击右侧舞者列表下方的 `+` 创建舞者并设置名称、编号和颜色。选中舞者后可拖动站位、编辑自定义形象或删除；所有站位变化都会记录到当前关键帧。

### F 键拍打

播放音乐时可以直接跟随节拍操作：

1. 按下 `F`：建立关键帧并进入绿色“保持队形”区间。
2. 松开 `F`：进入红色“队形变化”区间。
3. 再次按下 `F`：建立下一关键帧并开始新的保持区间。
4. 按空格暂停：保存当前区间并自动补充结束帧。

拍打完成后，可以在时间轴上继续微调每个红绿区间。

### 快速操作

![Windows 右下快速操作](docs/images/readme/windows-quick-actions.png)

快速操作集中在编辑器右下角，包括：

- 对齐网格：整理当前关键帧中略有偏差的站位。
- 快速编队：根据舞者人数生成预设队形。
- 清除选择：取消当前舞者选择，不删除数据。
- 显示名字：显示或隐藏舞台上的舞者姓名。
- 复制前帧：把上一关键帧站位复制到当前时间。
- 复制/粘贴关键帧：在不同时间点复用完整队形。
- 显示 C 位线：检查中心位和左右对称。
- 翻转方向：切换观众视角与舞者视角。
- 自动吸附：控制拖动时是否吸附到舞台网格。

## Android 手机版

![Android 手机版编辑器](docs/images/readme/android-editor.png)

手机版采用横屏触控布局，适合排练现场快速查看和调整队形。左侧导入参考视频，底部导入音频，中央舞台可直接拖动舞者；顶部提供复制前帧、撤销、预览、捕捉和 3D 功能。

舞台右下的圆形 `F` 按钮对应电脑版键盘 `F`，按住、松开和再次按住同样可以实时建立红绿变化区间。

### 手机版创建舞者

![Android 手机版创建舞者](docs/images/readme/android-add-dancer.png)

导入媒体后点击右上角“舞者 +”，可以填写名称、选择颜色，也可以直接使用 MUSE、Aqours、虹咲、Liella! 等成员名称预设。长按已有舞者可以继续编辑。

## 构建环境

- Windows 10/11 x64
- JDK 21（Android 也可使用 JDK 17）
- Android SDK 36

仓库包含 Maven Wrapper 和 Gradle Wrapper，不需要单独安装 Maven 或 Gradle；首次构建会自动下载依赖。

## 获取源码

```powershell
git clone https://github.com/SnowfallC/dancefactory.git
cd dancefactory
```

## 构建 Windows 电脑版

```powershell
.\mvnw.cmd test
.\mvnw.cmd package -DskipTests
.\build-exe.bat
```

- JAR：`target/formation-tool-desktop-1.1.5.jar`
- 分发包：`dist/windows/dancefactory-windows-1.1.5.zip`

## 构建 Android 手机版

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
