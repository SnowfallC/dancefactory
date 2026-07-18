# 安全策略

## 支持版本

安全修复仅保证进入最新发布版本。使用者应升级到最新正式版。

## 私下报告漏洞

请使用 GitHub Private Vulnerability Reporting：

https://github.com/SnowfallC/dancefactory/security/advisories/new

请不要在公开 Issue 中披露可利用细节、恶意工程文件或签名材料。报告中尽量提供受影响版本、平台、复现步骤、影响与建议修复方式。维护者会在确认后协调修复和披露时间。

## 信任边界

- `.ftproj` 是不可信输入，导入器必须保持条目白名单、路径校验和解压上限。
- 更新清单使用仓库内置 RSA 公钥验证；仅 SHA-256 不构成独立的来源认证。
- Android/Windows 发布物必须由维护者签名。更新私钥和 Android keystore 永远不得提交到 Git。
