# Trae 自动签到 LSPosed 模块

> ⚠️ **不提供免 Root 版本。** 本模块必须 Root + LSPosed 才能工作；如果你需要免 Root 使用，请自行修改源码实现。

> 一个 LSPosed 模块，注入 Trae 手机端客户端（com.bytedance.trae.cn），实现每日无人值守自动签到领积分。
> 目标环境：Android 8.0+（minSdk 26）/ LSPosed（libxposed API 102）· 当前版本 **v2.1**

**仅供学习研究 Xposed 模块开发技术使用。请自行评估并遵守目标应用的服务条款，使用本项目产生的任何后果由使用者自行承担。**

---

## 功能

- **App 直签（核心）**：模块 App 直接完成签到——root 读取 Trae 私有凭证（JWT + device_id）后直接请求 Trae 服务端接口，**全程不拉起 Trae**，签到完静默通知结果
- **定时签到**：自定义时间 + 随机偏移量（模拟真实节奏）。闹钟使用 `AlarmManager.setAlarmClock`（闹钟级 API，MIUI 自启动管控不拦截，进程被杀也会被拉起）；当日已签自动跳过，重启后自动恢复布防
- **签到结果通知 + 余额**：签到成功/失败（含"今日已签"）发系统通知，**通知与 Toast 直接显示本次积分与剩余余额**；签到后自动查询一次余额并写回状态
- **状态卡实时刷新**：打开模块 App 即刷新余额（直连 ent_usage 接口）；修改定时时间/偏移立即更新显示
- **Trae 深色模式**：对 Trae 每个窗口挂亮度反转硬件层（白→黑、色相保持），**冷启动首屏即深色**（开关落盘宿主目录，注入时预读，无框架就绪等待）；模块自身深浅支持跟随系统或手动选择，与 Trae 深色相互独立
- **漏签兜底**：直签失败自动回退拉起 Trae（后台压回）由 hook 引擎补签，3 分钟后检查并通知结果；Trae 运行中由引擎每分钟巡检兜底
- **日志**：模块 App 自身日志持久化到 `files/logs/app_*.log`（每日轮转，保留 5 天）；导出一键保存到 Download（App 侧日志为主体 + hook 侧日志镜像附加，不依赖 Trae 运行）
- **Token 过期预警**：7 天内过期发送系统通知
- **Trae 内注入入口**：在 Trae 积分页注入签到入口（CreditsUiHook）

## 权限说明

| 权限 | 用途 | 没有它会怎样 |
| --- | --- | --- |
| **通知** | 接收签到结果（成功/失败 + 积分与余额）、Token 过期预警 | 签到照常进行，但看不到任何结果提示 |
| **自启动**（MIUI 手动开启） | 定时签到到点时让系统能拉起模块执行 | 模块进程被杀后定时签到可能到点无人执行 |
| **Root（su）** | 读取 Trae 私有凭证与签到状态、写回结果、写深色开关、拉起 Trae 兜底 | 直签不可用，只能依赖"Trae 运行中"的 hook 引擎路径 |

设置页「权限设置」区块内可一键跳转：通知权限（弹出说明 → 系统授权/通知设置页）、自启动（弹出说明 → MIUI 应用详情页手动允许）。

> 首次打开会检测 **Root** 与 **LSPosed 授权**，缺少任一项会弹窗引导前往设置（这两项是硬性要求）；通知/自启动不强制。

## 工作原理

```
模块 App 进程（主路径：直签）
├── App                 # 入口：日志初始化、定时布防、通知权限 root 静默授予
├── SettingsActivity    # 设置界面：状态卡（余额/定时/注入状态）、权限申请、日志导出
├── LocalSigner         # 直签：su 读凭证 → status 查询 → claim 重试(0/10/30s) → 余额 → 通知
├── SchedLauncher       # 定时调度：setAlarmClock 布防 → 到点直签（失败回退拉起 Trae）
└── util/L              # 日志：内存环形缓冲 + App 侧文件持久化 + LSPosed 日志桥接

宿主进程（com.bytedance.trae.cn，兜底路径）
├── XposedEntry         # 入口：onPackageLoaded 抓 Context / onPackageReady 装载 Hook
├── HookLoader          # Hook 安装调度
├── SignInEngine        # 签到引擎（Trae 运行时兜底）：启动补签 + 分钟 tick + 每日闹钟
├── Creds / TraeApiSource  # 凭证提取 / 远程接口源（COS 配置，失败回退缓存或内置）
├── DarkModeHook        # Trae 深色：窗口硬件层亮度反转（启动即预读开关文件）
└── CreditsUiHook       # Trae 积分页注入签到入口 + 余额刷新

跨进程
├── 宿主状态文件 /data/data/com.bytedance.trae.cn/files/trae_signin_state.json（权威源，App 经 root 读写）
└── LSPosed remote prefs（设置下发 + hook 日志镜像）
```

签到接口地址不打包死在 APK 里：签到前从腾讯云 COS 拉取 `api_source.json`（30 分钟静默期，版本回退保护），失败沿用本地缓存或内置兜底地址——接口变更无需发版。

## 安装

1. 安装 APK（需 Root + LSPosed，libxposed API 102）
2. 在 LSPosed 管理器中启用模块，作用域勾选 **Trae**
3. 打开模块 App「TRAE Pro」，按需开启通知/自启动权限
4. 完成——定时与手动签到均不再需要打开 Trae

## 自行构建

```bash
# Android Studio 打开项目 → Build → Make Project，或：
gradle assembleRelease        # 产物：app/build/outputs/apk/release/app-release.apk
```

> 注意：本项目基于 LSPosed fork（API 102），`app/libs/` 中内置 fork 提供的服务层 AAR（XposedService / HotReload），标准 LSPosed 可能不兼容。

## 参考

- [LSPosed](https://github.com/LSPosed/LSPosed)
- [libxposed API](https://github.com/libxposed/api)

## License

[GPL-3.0](LICENSE)
