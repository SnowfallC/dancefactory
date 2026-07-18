# 第三方软件声明

DanceFactory 源码采用 GPL-3.0-or-later。构建和发行包还使用以下主要第三方组件；完整传递依赖以 Maven/Gradle 锁定结果为准。

| 组件 | 当前版本/来源 | 主要许可证 |
| --- | --- | --- |
| OpenJFX | 21.0.1 | GPL-2.0 with Classpath Exception |
| Gson | 2.10.1 | Apache-2.0 |
| JavaCV / JavaCPP Presets | 1.5.10 | Apache-2.0；其预设包装的原生项目另有许可证 |
| FFmpeg | 6.1.1-1.5.10 | LGPL-2.1-or-later；启用 GPL 组件的构建为 GPL-2.0-or-later |
| Kotlin / kotlinx.coroutines | 1.9.20 / 1.7.3 | Apache-2.0 |
| AndroidX / Jetpack Compose / Room | 见 `app/build.gradle` | Apache-2.0 |
| Material Components for Android | 1.11.0 | Apache-2.0 |
| JUnit 5 / JUnit 4 | 5.10.2 / 4.13.2 | EPL-2.0 / EPL-1.0 |

## FFmpeg 分发说明

桌面端通过 Bytedeco JavaCPP Presets 加载 FFmpeg 原生库。发布者必须核验实际发行包中 FFmpeg 的构建配置，并随发行物提供对应许可证、版权声明、准确源码或等效源码获取方式。若使用带 GPL 组件的构建，整个组合分发必须满足 GPL 要求。

- FFmpeg legal: https://ffmpeg.org/legal.html
- JavaCV: https://github.com/bytedeco/javacv
- JavaCPP Presets: https://github.com/bytedeco/javacpp-presets
- OpenJFX: https://openjdk.org/projects/openjfx/

每次升级依赖后都应重新生成依赖清单，并检查本文件是否仍准确。本文件不是法律意见。
