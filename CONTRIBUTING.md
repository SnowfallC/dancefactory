# 贡献指南

感谢参与 DanceFactory。

## 开始前

1. 先搜索已有 Issue；安全问题按 `SECURITY.md` 私下报告。
2. 从最新 `main` 创建短分支。
3. 不要提交个人媒体、真实工程、签名文件、密钥、`local.properties` 或构建产物。

## 代码要求

- Java/Kotlin 源码使用 UTF-8 和 LF；批处理文件使用 CRLF。
- 不可信文件输入必须有大小限制、路径规范化和明确白名单。
- 影响预览或导出的修改应同时检查桌面端与 Android 端。
- 新行为应补测试；媒体回归样本必须可公开再分发并注明来源。

## 提交前验证

```powershell
.\mvnw.cmd test
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
git diff --check
```

Pull Request 请说明变化、原因、用户影响、验证方式；修复问题时补充根因。
