# `.ftproj` 工程格式

`.ftproj` 是 ZIP 容器。1.1.6 导入器只接受以下结构：

```text
project.json
audio.<mp3|wav|m4a|aac|ogg|flac>        可选，最多一个
video.<mp4|m4v|mov|webm|mkv>            可选，最多一个
dancer_styles/<safe-dancer-id>.png      可选
```

路径区分大小写，不允许绝对路径、反斜杠、`..`、嵌套媒体目录或其他条目。ID 只能包含 ASCII 字母、数字、下划线和连字符，长度 1–128。

安全上限：最多 256 个条目；`project.json` 最大 2 MiB；单张样式最大 20 MiB；单个媒体最大 2 GiB；总解压内容最大 3 GiB。实现还限制工程名、舞台尺寸、队形和舞者数量。

兼容性规则：未知 JSON 字段可被忽略，但必要字段、ID 和结构必须有效。应用不会执行工程中的任何代码。导出的工程可能包含用户原始媒体，分享前应确认版权、隐私与文件体积。
