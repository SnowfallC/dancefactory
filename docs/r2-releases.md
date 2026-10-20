# R2 自动更新发布

Windows 和 Android 从同一个签名清单检查版本：

`https://pub-02ac16656b17473f97b1b838e1bc546d.r2.dev/dancefactory/updates/latest.json`

R2 桶名称为 `dancefactory`。R2 的登录凭据只保存在发布机器上，不放入客户端或仓库。客户端只包含更新验签公钥。

## 1.1.6 的一次性迁移

1.1.6 更换了更新清单签名密钥，旧版用户需要手动下载并安装一次 1.1.6。Android 安装包沿用原签名，包名不变，可直接覆盖安装；不要先卸载旧版。

1.1.6 同时信任当前公钥和历史公钥。历史签名样本保持原样，新发布清单使用 `.signing/update-private-key.pem`。APK 签名密钥与更新清单私钥是两套不同的密钥，不能相互替代。

## 本机发布

发布机需要标准 Node.js、Python 3.11+、Java 和 Android SDK build-tools。首次运行或 Cloudflare 登录过期时，执行并在浏览器完成官方授权：

```powershell
npx --yes wrangler@4.151.2 login --scopes account:read user:read workers:write
```

先生成两端安装包，放入 `target/release-<版本>/`：

- `dancefactory-windows-<版本>.zip`：包含完整应用目录、运行时、播放组件与导出工具。
- `dancefactory-android-<版本>-release.apk`：原证书签名的正式 APK。

本机 APK 签名可继续使用 Android Studio 已保存的凭据；无需把密码复制到聊天或脚本中。

```powershell
# 默认只生成本地签名清单，不修改 R2。
node "tools/publish-update.mjs"

# 确认产物后发布到已登录账户的 dancefactory 桶。
node "tools/publish-update.mjs" --publish
```

版本默认从 `pom.xml` 读取，并与 Android `versionName` 比对。更新内容从 `CHANGELOG.md` 当前版本章节读取。可用 `--private-key` 指定原私钥的安全备份路径。

脚本会检查两端安装包确实包含当前公钥，验证 APK 原证书、包名和版本，以及 Windows 包内运行时和播放组件；随后校验私钥并生成包含文件大小和 SHA-256 的 RSA 签名清单。上传后从公网完整下载校验两个安装包，最后写入版本化清单和 `latest.json`。安装包使用长期缓存，清单使用 `no-store`。任何安装包校验失败都不会切换线上清单。已存在但内容不同的同版本安装包会被拒绝覆盖。

发布不会替用户点击安装：Android 仍需系统安装确认；Windows 使用现有退出后替换流程。

Windows 压缩包通过 `tools/package_windows_release.py` 按发行文件名单生成，包含程序、运行时、实际依赖和许可证，不包含开发验证文档、报告、变更记录或构建日志。发布前也会检查该文件名单。

需要修正已经发布的同版本 Windows 包时，使用新的文件名以避免旧地址的长期缓存；APK 不变则沿用原地址。例如：

```powershell
python "tools/package_windows_release.py" --source "target/release-1.1.6-windows/舞形工坊" --output "target/release-1.1.6/dancefactory-windows-1.1.6-release.zip" --version 1.1.6
node "tools/publish-update.mjs" --windows-file dancefactory-windows-1.1.6-release.zip --replace-manifest --publish
```

## 维护与备份

请安全备份 `.signing/update-private-key.pem`、安卓签名密钥库及相应密码。仅备份公钥或已签名 APK 无法恢复私钥。不要覆盖原始密钥，不要把私钥上传 R2。自动化发布可以使用具备目标桶对象读写权限的 Cloudflare API Token；无需修改客户端代码来保存上传凭据。

验证命令：

```powershell
node --test "tools/publish-update.test.mjs"
.\mvnw.cmd test
```

参考：[Cloudflare R2 公共桶](https://developers.cloudflare.com/r2/buckets/public-buckets/)、[Wrangler R2 命令](https://developers.cloudflare.com/r2/reference/wrangler-commands/)。
