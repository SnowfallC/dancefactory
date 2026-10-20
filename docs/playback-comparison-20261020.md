# 桌面播放内核对比：2026-10-20

选择 **libmpv 主播放 + FFmpeg 兼容播放/导出**，保留现有 JavaFX 界面和本机日志。

## 实测方法

- 本机 Windows x64、JDK 25、JavaFX 21.0.1；结果只代表本次机器、素材和适配实现。
- libmpv：v0.41.0-1224-g6a262d11f，shinchiro 20261020 通用 x86_64 构建。
- LibVLC：VideoLAN 官方 VLC 3.0.21 Windows x64。
- 两者均通过原生 C API 调用，关闭硬件解码，使用内存画面输出，避免显卡驱动差异。LibVLC 测试文件缓冲为 100ms。
- 合成 60 秒 H.264/AAC 素材：画面包含可读回的二进制帧编号，每秒同时闪光并播放 1kHz 提示音。基础视频为 640×360/30fps/GOP 150；高负载版本叠加运动测试图，1920×1080/60fps/GOP 300，帧编号仍按 30Hz 更新。
- 暂停后依次定位至 17.3、2.1、43.7、8.4、55.2、12.8、31.1、4.7、48.3、22.5 秒，读取实际输出画面编号，不能仅依赖播放器报告的位置。
- 再以每 20ms 一个目标发送 30 次拖动请求，检查最终目标；JavaFX 适配另测每 10ms 一个目标、连续 60 次请求。
- 音画测试比较 WASAPI 输出回环的提示音与视频回调/JavaFX 图层的闪光更新时间。音频按 48kHz、480 样本分块记录，以块返回时间的最小偏移建立样本时钟。回环初始化出现过 discontinuity 提示，因此偏差数值是软件观测估算，不能解释成屏幕与扬声器的物理端到端延迟。

## 结果

| 检查 | libmpv | LibVLC 3 |
| --- | --- | --- |
| 1080p60 素材，640×360 原生输出，10 次精确定位 | 全部命中目标帧，约 27–83ms，中位约 59ms | 直接暂停定位存在提前 2–4 个编号帧的情况；使用逐帧补偿后全部命中，约 275–575ms，中位约 350ms |
| 暂停定位接口 | `seek absolute+exact` | `set_time` 后读取实际帧，必要时调用 `next_frame` |
| 连续拖动后直接落到最终精确帧 | 通过 | 本次直接 `set_time` 路径未命中最终精确帧；释放后仍需补偿 |
| 基础素材，连续播放 55 秒 | 回环/视频回调偏差中位约 -25ms，绝对偏差 P95 约 26ms，首尾差约 0.3ms | 回环/视频回调偏差中位约 -16ms，绝对偏差 P95 约 16ms，首尾差约 -0.7ms |
| 实际 JavaFX 图层，1080p60 素材、1280×720 预览 | 10 次定位全部准确，复测约 62–139ms；55 秒播放偏差中位约 -25ms，绝对偏差 P95 约 26ms，首尾差约 1ms | 未完成同一 JavaFX 适配，不据原生输出结果宣称完整 UI 的耗时 |

负号表示画面回调/图层更新早于音频回环观测。两者的稳定播放同步都满足本轮观察；没有证据说明 libmpv 在所有格式或机器上比 LibVLC 更兼容。LibVLC 补偿耗时包括测试器的等待和逐帧策略，不是播放器不可突破的性能上限。选 libmpv 的主要依据是本项目需要频繁暂停拖动和准确队形定位，它直接提供的精确定位路径更简单。

## 最终实现

- 使用 JNA 直接接 libmpv；不引入 vlcj。主内核负责同一视频的音画同步，时间轴读取内核位置。
- JavaFX 线程仅显示最新画面；解码、定位和原生资源释放在后台完成。拖动请求合并，定位期间保留用户目标，避免旧时钟把时间轴拉回。
- 首帧超时、媒体解码失败或原生组件缺失时，记录原因并自动进入独立 FFmpeg 预览。FFmpeg 画面跟随提取的 PCM 音频时钟。
- 主预览 1280×720，兼容预览 640×360。预览尺寸不改变原片，也不改变导出素材。
- 后端只加载应用目录内的 libmpv；Windows 包自带 FFmpeg 与 Java 运行时。固定组件 URL、SHA256、许可和来源说明。
- 后续清理已移除旧 JavaFX 视频播放器、格式探测、代理播放器和相关调试入口，并移除 JavaCV/JavaCPP 依赖。JavaFX MediaPlayer 仅保留音频用途。
- 工程文件不依赖所选后端。跨机器打开时重新选择可用播放路径。
- 本机日志记录版本、组件路径、媒体文件、定位、失败与自动切换；错误弹窗和帮助菜单保留“打开日志文件夹”。

## 验证范围

- 普通单元测试 32 项通过。
- 实际 JavaFX 图层定位、连续拖动、暂停、播放到结尾再播放、关闭并重新创建播放器通过。
- HEVC/AAC 原始附件在实际导入控制器中连续加载三次并播放通过。
- 中文、空格、`#`、`&` 路径通过；无音轨视频播放通过。
- 模拟缺失主内核，自动提取 PCM、切换 FFmpeg、播放、拖动和暂停通过；兼容画面与音频时钟抽样偏差小于 200ms。
- 损坏媒体不会触发成功回调；保留错误日志入口。过期加载和释放后的请求不能重新激活旧媒体。
- 导出仍使用原片；同时发现并修正旧参考视频导出没有限制原片区间的问题，已验证 2–3 秒区间输出为约 1 秒。
- 实际 jpackage 启动器与裁剪运行时的副本验收通过：加载包内 libmpv、播放、自动切换包内 FFmpeg、日志写入、错误弹窗日志按钮均通过。测试入口仅加入验收副本，交付包不含该入口。

以上是本机测试，不代表报错用户的机器已验收。交付包仍需在对方机器用原始问题视频复测，失败时收集新日志。

## 复测入口与证据

- `tools/playback-benchmark/compare.py`：原生内核、帧编号及回环对比。需要 Python、numpy、soundcard；在独立环境安装，或将依赖放入测试目录的 `python/`。
- 默认素材和组件目录为 `target/player-comparison/`；用 `FORMATION_BENCHMARK_DIR` 可覆盖。LibVLC 放在 `vlc/vlc-3.0.21/`，libmpv 放在 `mpv/libmpv-2.dll`。
- `BENCHMARK_SOURCE` 指定素材；`PLAY_SECONDS=55` 控制播放长度；`VLC_STEP=1` 启用逐帧补偿；`RUN_SUFFIX` 保留不同轮次结果。两套音画测试须依次运行，避免声音互相污染。
- `tools/playback-benchmark/record-javafx-av.py`：启动 `MpvAvIntegrationTest`，记录实际 JavaFX 图层与 WASAPI 回环。需从项目根目录执行。
- `MpvVideoIntegrationTest`：通过 `mpvRegression` 指定编号素材；`DesktopPlaybackPathIntegrationTest`：通过 `playbackRegression` 指定真实媒体；两者通过 `formation.mpv.library` 指定测试 DLL。
- 主要 JSON、逐次定位结果、集成验证 XML 和打包验收结果保存在维护者本机的 `docs/playback-evidence-20261020/`，不随源码或发行包提交。复测脚本在本机生成对应结果。

## 上游资料

- [mpv 嵌入及播放接口](https://mpv.io/manual/stable/)
- [mpv 渲染接口](https://github.com/mpv-player/mpv/blob/master/include/mpv/render.h)
- [mpv Windows 构建来源](https://github.com/shinchiro/mpv-winbuild-cmake/releases/tag/20261020)
- [VideoLAN 3.0.21 Windows x64](https://download.videolan.org/pub/videolan/vlc/3.0.21/win64/)
- [LosslessCut 兼容格式与辅助播放](https://github.com/mifi/lossless-cut#supported-formats)
