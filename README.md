# TRAE Pro · Trae 自动签到 LSPosed 模块

> 一个 LSPosed 模块，注入 Trae 手机端客户端（com.bytedance.trae.cn），实现每日无人值守自动签到领积分。
> 目标环境：Android 8.0+ / LSPosed（libxposed API 102）· 当前版本 **v1.0.10**

**仅供学习研究 Xposed 模块开发技术使用。请自行评估并遵守目标应用的服务条款，使用本项目产生的任何后果由使用者自行承担。**

---

## 功能

- **开屏自动签到**：Trae 启动后 15s 内补签，引擎 60s tick 处理手动指令与失败重试
- **定时签到**：自定义时间 + 随机偏移量（模拟真实操作节奏），AlarmManager 兜底，当日已签自动跳过
- **远程接口源**：签到接口地址托管在腾讯云 COS（公有读 JSON），App 启动/签到前自动拉取更新，失败回退本地缓存或内置地址——接口变更无需发版
- **剩余积分展示**：与 Trae 面板同源同步
- **Trae 内注入入口**：在 Trae 设置面板注入「立即签到」胶囊按钮
- **签到历史**：近 30 天记录（BottomSheet）+ 漏签检测（近 7 天）
- **Token 过期预警**：7 天内过期发送系统通知
- **暗夜模式**：跟随系统（DayNight），可控制 Trae 本体夜间模式
- **云端账号管理**：多账号凭证同步（友爱账号 Uniai）

## 工作原理

```
宿主进程（com.bytedance.trae.cn）
├── XposedEntry          # 入口：onPackageLoaded 抓 Context / onPackageReady 装载 Hook
├── HookLoader           # Hook 安装调度
├── Creds                # 从宿主私有目录提取运行时凭证（token / device_id）
├── TraeApiSource        # 远程接口源：COS 拉取 → 落盘缓存 → 内置兜底（版本回退保护）
├── SignInEngine         # 主动签到引擎：状态查询 → 签到 → 去重 → 重试
│     ├── 去重：宿主 filesDir 状态文件为权威源（day 键 + 尝试护栏 + 失败上限）
│     ├── 调度：启动补签 + tick 轮询 + AlarmManager 每日闹钟（三级降级）
│     └── 双进程（main / :push）安全
└── CreditsUiHook        # Trae 设置面板注入签到入口 + 余额刷新
      └── DarkModeHook      # Force Dark opt-in，控制 Trae 本体夜间模式

模块 App 进程
├── App / SettingsActivity / LoginActivity   # 设置与账号（RemotePreferences 与宿主双向桥接）
├── cloud/{CloudApi, RemoteBases, TokenManager, Updater}  # 云端账号 API / 远程服务地址 / 凭证存储 / 自更新
└── util/L                                   # 日志桥接至 XposedModule.log
```

凭证不硬编码、不上传：引擎在宿主进程内运行时动态读取宿主自身已登录的凭证，请求也在宿主进程内发起。

### 远程配置（api_source.json）

参考 Hazuki 的远端源方案，两类地址均托管在腾讯云 COS（公有读 JSON），App 启动时自动下载：

1. **签到接口源**（hook 侧 [TraeApiSource.kt](app/src/main/java/com/example/traesignin/hook/TraeApiSource.kt)）：status / claim / ent_usage 三个接口地址
2. **云端服务地址**（App 侧 [RemoteBases.kt](app/src/main/java/com/example/traesignin/cloud/RemoteBases.kt)）：auth-center / trae-worker 地址——真实服务器地址不出现在源码里

```json
{
  "version": 2,
  "status_url": "https://api.trae.cn/trae/api/v2/ug/checkin_credits/status",
  "claim_url": "https://api.trae.cn/trae/api/v2/ug/checkin_credits/claim",
  "ent_usage_url": "https://api.trae.cn/trae/api/v2/pay/ide_user_ent_usage",
  "auth_base": "http://your-server:3010",
  "worker_base": "http://your-server:3011"
}
```

拉取策略（两处一致）：

- 进程启动/首次访问时拉取（30 分钟静默期），成功后覆盖本地缓存
- 拉取失败沿用上次缓存；签到源从未成功过则使用内置兜底地址
- 仅接受绝对 http(s) 地址；远端 `version` 低于当前版本时不覆盖（回退保护）

## 安装

> **先看这张表，按自己的设备环境二选一：**

| 你的设备 | 选哪个包 | 需要 Root？ | 需要 LSPosed？ |
| --- | --- | :---: | :---: |
| 已 Root + 已装 LSPosed（API 102） | `traesignin-x.x.x-release.apk`（标准模块） | ✅ 需要 | ✅ 需要 |
| **没有 Root / 没装任何框架** | `trae-npatch-x.x.x.apk`（免 Root 直装包） | ❌ 不需要 | ❌ 不需要 |

前往 [Releases](https://github.com/ZhaoyuanGuo/trae-pro-signin/releases) 下载。

### 方式一：标准 LSPosed 模块（需 Root + LSPosed）

适用于**已 Root 且已安装 LSPosed**（libxposed API 102）的设备。

1. 安装 `traesignin-x.x.x-release.apk`
2. 在 LSPosed 管理器中启用模块，作用域勾选 **Trae**
3. 打开模块 App「TRAE Pro」完成设置
4. 重启 Trae 生效

### 方式二：免 Root 直装包（**没有 Root、没有 LSPosed 框架的选这个**）

`trae-npatch-x.x.x.apk` 是用 [NPatch](https://github.com/7723mod/NPatch) 把本模块内嵌进 Trae 客户端后重新签名的**独立可安装包**——开箱即用，**不需要 Root、不需要 LSPosed、不需要任何 Xposed 框架**。

1. **先卸载设备上原有的 Trae**（包名相同、签名不同，无法覆盖安装；卸载会清除 Trae 本地登录数据，请自行确认）
2. 安装 `trae-npatch-x.x.x.apk`
3. 打开应用登录 Trae 账号即可，模块随宿主进程自动注入，无需任何额外配置

> 注意：该包内嵌的 Trae 客户端为**重打包版本**，仅供学习研究使用；后续 Trae 官方更新时，本包不会自动跟随升级。

### 自行构建

```bash
# Android Studio 打开项目 → Build → Make Project，或：
gradle assembleRelease        # 产物：app/build/outputs/apk/release/app-release.apk
```

> 注意：本项目基于 LSPosed fork（API 102，支持热重载），`app/libs/` 中已内置 fork 提供的服务层 AAR（XposedService / HotReload），标准 LSPosed 可能不兼容。

## 参考

- [LSPosed](https://github.com/LSPosed/LSPosed)
- [libxposed API](https://github.com/libxposed/api)
- [NPatch](https://github.com/7723mod/NPatch)（免 Root 打包工具）

## License

[GPL-3.0](LICENSE)
