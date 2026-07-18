# 桌面端测试数据契约

## 文件说明
- `project_minimal.ftproj`：最小工程，仅 `project.json`，用于冷启动与基础导入。
- `project_media.ftproj`：包含 `project.json` + `audio.mp3` + `video.mp4` + `dancer_styles/*.png`，用于媒体与样式链路。
- `project_stress.ftproj`：多队形数据样本，用于时间轴与读取压力验证。
- `media/sample-audio.wav`：占位音频样本（非真实内容，仅用于文件链路测试）。
- `media/sample-video.mp4`：占位视频样本（非真实内容，仅用于文件链路测试）。
- `media/README.md`：媒体样本使用说明。

## 注意事项
- `.ftproj` 本质为 ZIP 包，内部至少包含 `project.json`。
- 自动化回归优先使用本目录数据，避免依赖个人环境媒体文件。
