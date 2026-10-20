# 第三方软件声明

DanceFactory 源码采用 GPL-3.0-or-later。构建和发行包还使用以下主要第三方组件；完整传递依赖以 Maven/Gradle 锁定结果为准。

| 组件 | 当前版本/来源 | 主要许可证 |
| --- | --- | --- |
| OpenJFX | 21.0.1 | GPL-2.0 with Classpath Exception |
| Gson | 2.10.1 | Apache-2.0 |
| JNA | 5.17.0 | LGPL-2.1-or-later / Apache-2.0 |
| libmpv Windows x86_64 | v0.41.0-1224-g6a262d11f，shinchiro 20261020 构建 | 当前构建按 GPL 分发，包含原生依赖 |
| FFmpeg 命令行 | 发行包内 `ffmpeg.exe`；本次预览为 8.0.1 essentials，Gyan 构建 | 本次启用 GPL/version3，按 GPL-3.0-or-later 分发 |
| Kotlin / kotlinx.coroutines | 1.9.20 / 1.7.3 | Apache-2.0 |
| AndroidX / Jetpack Compose / Room | 见 `app/build.gradle` | Apache-2.0 |
| Material Components for Android | 1.11.0 | Apache-2.0 |
| JUnit 5 / JUnit 4 | 5.10.2 / 4.13.2 | EPL-2.0 / EPL-1.0 |

## FFmpeg 分发说明

桌面主播放使用应用内置 libmpv，通过 JNA 调用，不依赖 vlcj。固定构建来源、SHA256、上游源码和构建记录见 `tools/prepare-playback-runtime.ps1`；准备脚本会将 mpv 版权、GPL/LGPL 文本和来源说明放入发行目录 `app/native/mpv/`。

桌面端使用独立 FFmpeg 命令行进程，不再加载 JavaCV/JavaCPP 原生库。发布者必须核验实际发行包中 FFmpeg 的构建配置，并随发行物提供对应许可证、版权声明、准确源码或等效源码获取方式。若使用带 GPL 组件的构建，整个组合分发必须满足 GPL 要求。

- FFmpeg legal: https://ffmpeg.org/legal.html
- OpenJFX: https://openjdk.org/projects/openjfx/

每次升级依赖后都应重新生成依赖清单，并检查本文件是否仍准确。本文件不是法律意见。
