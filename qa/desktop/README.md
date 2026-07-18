# 桌面端验证入口

## 一键全量验证
```powershell
powershell -ExecutionPolicy Bypass -File "qa/desktop/run-verify.ps1"
```

## 一键全量验证（自动标记人工项）
```powershell
powershell -ExecutionPolicy Bypass -File "qa/desktop/run-verify.ps1" -manualUiPass -markSmokePass
```

## 一键验证（跳过打包，仅构建+测试+冒烟）
```powershell
powershell -ExecutionPolicy Bypass -File "qa/desktop/run-verify.ps1" -skipPackaging
```

## 仅打包验证
```powershell
powershell -ExecutionPolicy Bypass -File "qa/desktop/run-packaging-verify.ps1"
```

## 生成冒烟清单
```powershell
powershell -ExecutionPolicy Bypass -File "qa/desktop/run-smoke.ps1"
```

## 结果文件
- 环境检查：`qa/desktop/reports/env-check.txt`
- 测试日志：`qa/desktop/reports/test-run.txt`
- 打包日志：`qa/desktop/reports/packaging-check.txt`
- 冒烟清单：`qa/desktop/reports/smoke-checklist.json`
- 总结报告：`qa/desktop/reports/verification-summary.json`
