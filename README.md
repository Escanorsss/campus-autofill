# 校园认证助手（CampusAutofill）

识别任意软件里拉起的**学校统一身份认证**登录页（aTrust、教务、门户……），经你确认并通过**指纹 / 锁屏密码**验证后，自动填入账号密码。

- **两步式交互**：检测到登录页 → 弹「是否使用账号 xxx 的密码填充？」→ 点［填充］→ 指纹/锁屏验证 → 自动填入
- **只填不点**：绝不点「登录」按钮；页面带验证码/短信验证时只填账号密码，后续手动
- **每次填充都验证**：无免验时间窗
- **零网络**：App 无 INTERNET 权限；密码用 Android Keystore AES-256-GCM 加密，验证通过后才在内存解密，明文永不落盘
- **不动 OPPO 记忆密码**：走无障碍（AccessibilityService）路线，不注册系统自动填充服务

## 下载安装

[下载最新 APK](https://github.com/Escanorsss/campus-autofill/releases)：在 Assets 中选择 `.apk` 文件，下载后直接安装，无需编译。支持 Android 8.0 及以上；当前提供已签名的 Debug 预览版。

## 首次使用（App 内按设备展示权限状态和引导）

1. 保存账号密码（先通过指纹 / 锁屏验证），开启「校园认证助手（自动填充）」无障碍服务。
2. Android 13+ 侧载安装若提示「受限制的设置」，先到应用详情 → 右上角菜单 → 允许受限制的设置，再开启无障碍。无此菜单且能正常开启时跳过。
3. 若后台确认页不能弹出，开启「显示在其他应用上层」，并检查厂商的后台弹出界面权限。
4. 按需要允许系统电池优化豁免，再按设备引导检查自启动 / 后台运行；两者独立，系统白名单不代表厂商限制已解除。
5. 若最近任务支持锁定 / 保留，可以开启以减少一键清理的影响。通知授权可选，当前没有常驻通知保活功能。

返回主界面会重新读取系统授权状态。无障碍开关打开但服务未连接时，会单独提示重开服务和检查后台限制。受限制设置、自启动、后台运行和最近任务锁定没有可靠的通用查询接口，显示「需手动确认」，不会因点击设置按钮就标成已授权。

| 设备家族 | 引导重点 |
|---|---|
| OPPO / 一加 / realme | ColorOS / OxygenOS / realme UI：自启动、耗电管理中的后台运行；部分子页面仅允许厂商应用访问，使用应用详情和手动搜索 |
| 小米 / Redmi / POCO | MIUI / HyperOS：自启动、省电无限制、其他权限中的后台弹出界面；重装后重新检查授权 |
| vivo / iQOO | OriginOS / Funtouch OS：自启动、后台高耗电 / 后台耗电管理 |
| 华为 | EMUI / 兼容 Android 的 HarmonyOS：应用启动管理改为手动管理，允许必要的启动和后台活动 |
| 荣耀 | MagicOS：应用启动管理和应用电池设置；单独识别品牌，避免部分旧设备误判为华为 |
| 三星 | One UI：后台使用限制、休眠 / 深度休眠、从不休眠的应用 |
| 原生 / 其他 Android | 应用电池与后台运行设置；找不到专用入口时使用通用设置 |

设备家族通过 manufacturer / brand 选择，菜单路径仅供查找，不保证每个系统版本都相同。设置跳转按标准页面 → 应用详情 → 系统设置兜底，并处理页面不存在或厂商拒绝访问的情况；三星后台管理尝试其官方「从不休眠应用」入口。当前没有 iOS / 鸿蒙 NEXT 原生实现。

权限引导参考 [Jev 安卓版主页实现](https://github.com/jev-chat/jev-chat-jarvis/blob/fdf8d28c811b0b67016d815df674cc61603c33c8/cn/app/src/main/java/com/jev/probe/MainActivity.kt) 的实时清单和 [后台设置说明](https://github.com/jev-chat/jev-chat-jarvis/blob/fdf8d28c811b0b67016d815df674cc61603c33c8/cn/README.md)，本项目独立实现，未引入其聊天、网络或前台保活模块。版本差异以 [Android 通知权限](https://developer.android.com/develop/ui/compose/notifications/notification-permission)、[受限制设置](https://support.google.com/android/answer/12623953)、[华为后台管理](https://consumer.huawei.com/en/support/content/en-us00428704/) 和 [三星应用管理](https://developer.samsung.com/mobile/app-management.html) 文档为依据。

## 自测（不用校园网）

App 主界面 →「打开模拟登录页自测」→ 3 个变体：

| 变体 | 覆盖场景 | 预期 |
|---|---|---|
| A 标准表单 | username/password 命名 | 弹确认 → 指纹 → 两框填入，不点登录 |
| B 中文+验证码 | 学号/密码 + 图形验证码 | 只填学号密码，验证码留白 |
| C 异构 id | unPassword/pw 等奇葩命名 | 仍能识别填入 |

点［取消］后同一页面不再弹；离开该页面再回来会重新询问。

## 性能说明

- 常驻内存约 20~40MB；主界面「暂停监听」一键归零
- 只在窗口切换时做识别分析（每天几次到几十次），空闲时近零开销；不持 GPS/网络/WakeLock
- 主界面显示「工作中 / 已暂停 / 未连接」；当前未实现常驻通知，不依赖通知权限运行

## 构建

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

JDK 17 + Android SDK（platform 36）；AGP 8.12.0 / Kotlin 2.1.20 / Gradle 9.3.1。若迁移 SDK 目录，需同步更新未纳入 Git 的 `local.properties`。

权限适配验证（2026-10-08）：debug 构建和 Android Lint 通过（0 errors；仍有警告）；16 组设备识别用例通过，覆盖品牌回退、旧荣耀的 HUAWEI 厂商标识、未知品牌及土耳其语区域设置。OPPO / Android 16 / ColorOS 16.1 真机验证了无障碍、悬浮窗、电池豁免申请、应用详情、通知设置的入口；未自动改变系统授权。其他厂商目前仅完成代码及文档核对，尚未真机验证。另修正 Android 8 剪贴板清理兼容和确认页返回手势处理。

## 已知边界

- 只处理「用户名 + 密码」成对出现的表单；纯改密页等不触发
- 换锁屏密码/指纹可能导致 Keystore 密钥失效 → 重新保存一次凭据即可
- 鸿蒙 NEXT 不支持（安卓 APK）

