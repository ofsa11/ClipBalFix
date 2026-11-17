# Changelog

## [2.2] - 2026-11-18

- 应用名改为 **ClipBalFix**
- 包名改为 `com.clipbal.fix`
- 代码结构整理为标准 Gradle 工程

## [2.1] - 2026-11-18

- 改用现代 libxposed API 102（`XposedModule` + `META-INF/xposed` 元数据）
- 新增主 Hook：`ClipboardOverlayView.setActionChip(RemoteAction, Runnable)`
  - 把 AIS 创建的 `RemoteAction` 替换为 SystemUI 自己创建的 `PendingIntent`
  - 解决 Android 15+ BAL 硬化导致的点击无响应
- 新增兜底 Hook：`PendingIntent.send()` 非 Context 重载
- 新增 Hot Reload 支持（`onHotReloading` / `onHotReloaded`）

## [2.0] - 2026-11-18

- 首个版本：Hook `PendingIntent.send()`，用 SystemUI Context 直接 `startActivity`

## [1.0]

- 初始试验版本（未生效）
