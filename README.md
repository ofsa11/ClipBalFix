# ClipBalFix

修复 Android 15+ / Android 17 上 **智能剪贴板浮窗「開啟」按钮点击无反应** 的 LSPosed 模块。

> 适用场景：类原生 / AOSP 定制 ROM（如 Evolution X、LineageOS）复制链接后，剪贴板浮窗显示 Chrome「開啟」按钮，但点击没有任何反应，也不报错。

---

## 问题现象

1. 在任意 App 里复制一段链接；
2. 系统弹出剪贴板浮窗，带分享、Chrome「開啟」等按钮；
3. 点击「開啟」→ **什么都不发生**，浮窗正常收起。

Logcat 里可以看到（system_server）：

```
E ActivityTaskManager: Background activity launch blocked! goo.gle/android-bal
    callingPackage: com.google.android.as
    callingPackageTargetSdk: 37
    intent: Intent { act=android.intent.action.VIEW dat=https://... }
    isPendingIntent: true
    originatingPendingIntent: PendingIntentRecord{ ... com.google.android.as startActivity }
    realCallingPackage: com.android.systemui
    balAllowedByPiCreator: BSP.NONE
E ActivityTaskManager: Abort background activity starts from 10213
```

## 根因

Android 15 起引入、17 继续收紧的 **BAL（Background Activity Launch）硬化**：

- 当 App A 发送 App B 创建的 `PendingIntent` 时，**只认创建方 B 在创建时显式授予的后台启动权限**，发送方 A 的权限不算数；
- AOSP 源码里的兼容性开关：

```java
/** If enabled the creator will not allow BAL on its behalf by default. */
@ChangeId
@EnabledAfter(targetSdkVersion = UPSIDE_DOWN_CAKE)
private static final long DEFAULT_RESCIND_BAL_PRIVILEGES_FROM_PENDING_INTENT_CREATOR = 296478951;
```

- 智能剪贴板浮窗的「開啟」按钮来自文本分类器返回的 `RemoteAction`，它的 `PendingIntent` 由 **`com.google.android.as`**（Android System Intelligence）创建；
- AIS 创建时**没有 opt-in**（没有 `ActivityOptions.setPendingIntentCreatorBackgroundActivityStartMode(MODE_BACKGROUND_ACTIVITY_START_ALLOWED)`），因此 `balAllowedByPiCreator = BSP.NONE`；
- 发送方 SystemUI 的默认授权是 `ALLOW_FGS`（只够启动前台服务，`allowsBackgroundActivityStarts() == false`），救不了这次启动；
- 最终 system_server 直接 `Abort`，且**不弹任何提示**。

这是系统组件之间的适配缺口，不是用户设置问题。

## 实现原理

不修改 AIS，也不修改系统，而是在浮窗**创建按钮的那一刻**把 `RemoteAction` 换成 SystemUI 自己创建的 `PendingIntent`：

```text
Hook: com.android.systemui.clipboardoverlay.ClipboardOverlayView#setActionChip(RemoteAction, Runnable)
  ↓
从原 RemoteAction 取回 Intent（PendingIntent.getIntent() 反射）
  ↓（失败则从剪贴板兜底重建，SystemUI 持有 READ_CLIPBOARD_IN_BACKGROUND）
PendingIntent.getActivity(SystemUI Context, ...)  ← 创建方变成 SystemUI
  ↓
new RemoteAction(icon, title, desc, newPendingIntent) → chain.proceed(args)
```

这样点击时 **创建方 = 发送方 = SystemUI（同一 uid）**，命中 AOSP 的 `AUTO_OPT_IN_SAME_UID` 分支，`mBalAllowedByPiSender = ALLOW_BAL`，再叠加 SystemUI 的可见浮窗（non-app window），启动被放行。

兜底：同时 Hook `PendingIntent.send()` 的 4 个非 Context 重载，若仍有 AIS 创建的 VIEW PendingIntent 被发送，则改用 SystemUI Context 直接 `startActivity`。

## 兼容性

| 项目 | 要求 |
|---|---|
| Android | 14+（minSdk 34），主要在 Android 17 测试 |
| 框架 | 支持 **libxposed API 102** 的 LSPosed（LSPosed 2.x） |
| 作用域 | `com.android.systemui` |
| 模块 API | `minApiVersion=101`, `targetApiVersion=102` |

已在 **Xiaomi MIX 4 (odin) / Evolution X 17.0 (Android 17, SDK 37)** 上验证通过。

## 安装

1. 从 [Releases](../../releases) 下载最新 APK；
2. 安装 APK；
3. 打开 **LSPosed 管理器 → 模块 → 启用「ClipBalFix」**；
4. 确认作用域包含 **系统界面 (`com.android.systemui`)**；
5. 重启 SystemUI 或重启手机；
6. 复制一个链接，点击浮窗上的「開啟」即可正常跳转。

## 验证是否生效

```bash
adb logcat -s ClipBalFix
```

正常应看到：

```
event=module_loaded process=com.android.systemui api=102 framework=LSPosed ...
event=overlay_hook_ok method=...ClipboardOverlayView.setActionChip(...)
event=hooks_installed overlay_hooks=1 send_hooks=4
event=rebuild_action target=https://... original_creator=com.google.android.as
event=action_chip_replaced
```

## 构建

### 方式一：Android Studio

直接用 Android Studio 打开本工程，等待 Gradle 同步完成后 **Build → Build App Bundle(s) / APK(s) → Build APK(s)**。

### 方式二：命令行（Windows / PowerShell，无需 Gradle）

需要 Android SDK（`ANDROID_HOME`）与 JDK 17+：

```powershell
.\tools\build.ps1 -Keystore .\keystore\release.jks
```

首次运行会自动生成一个测试签名（`keystore/release.jks`，密码 `android`）；正式发布请换成你自己的签名。

### 方式三：命令行（Linux / macOS）

```bash
./tools/build.sh
```

## 项目结构

```text
ClipBalFix/
├── app/
│   ├── build.gradle.kts
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/clipbal/fix/ModuleEntry.java
│       ├── res/values/strings.xml
│       └── resources/META-INF/xposed/
│           ├── module.prop      # minApiVersion / targetApiVersion / staticScope
│           ├── java_init.list   # 入口类
│           └── scope.list       # com.android.systemui
├── tools/
│   ├── build.ps1
│   └── build.sh
├── .gitignore
├── LICENSE
└── README.md
```

## 已知限制

- 只处理 `ACTION_VIEW` + `http/https` 的链接类型 action chip；
- 需要 LSPosed 2.x（libxposed API 102）；
- 若 ROM 的剪贴板浮窗实现不是 AOSP `ClipboardOverlayView`，需要自行适配类名。

## 免责声明

本项目仅供学习与研究使用。修改系统行为存在风险，请自行备份并在可控设备上测试。

## License

[MIT](LICENSE)
