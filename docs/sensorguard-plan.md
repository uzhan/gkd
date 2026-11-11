# SensorGuard 传感器防误触 — 实施计划

本文件是后续里程碑的唯一计划源。文中 ⚠️ 已在 **M0** 用仓库真实符号替换；实现时以「真实符号对照表」为准。

## 0. 总规则

1. 类名 / Flow / 包名以本文件对照表为准，不得凭空假设。
2. 新增代码放在 `li.gkd.app.sensorguard`（及必要的 androidMain 平台实现）。对 GKD 原有文件只做最小接入。
3. 每完成一个里程碑（M0~M5）停下汇报；不要一次做完。
4. 不重构 GKD 现有代码，不改规则引擎（DSL / 选择器 / 订阅解析）。
5. 不引入新第三方依赖；确需时先说明理由。
6. 提交前确保 `./gradlew :gkd-android:assembleGkdDebug` 通过（本仓库无顶层 `assembleDebug`；渠道为 `gkd`）。
7. LICENSE：**GPL-3.0-only**（根目录 `LICENSE`）。本 fork 仅自用侧载，不上应用商店；衍生代码需遵守 GPL-3.0。

## 1. 背景与目标

**问题**：部分 App 开屏广告用 SDK 监听加速度计/陀螺仪实现「摇一摇/扭一扭」跳转。

**目标**：白名单 App 进入前台时临时冻结系统传感器若干秒，开屏结束后恢复。

**不做**：不屏蔽广告本身；不做传感器伪造；不改 GKD 规则引擎。

**核心机制**（已在目标机 PCHM10 上用 adb 验证）：

```
冻结：dumpsys sensorservice restrict <不存在的包名>
恢复：dumpsys sensorservice enable
```

执行身份：经 **Shizuku 启动的特权 UserService**（shell uid）执行；普通 app 进程无权限。

## 2. 架构

```
GKD：无障碍 / 任务栈 → AndroidForegroundState.foregroundRulesFlow（含 TopActivity）
     Shizuku → Privilege 启动 UserService（当前无通用 shell，M1 需补）
     FileSettingsStorage + kotlinx.serialization
     设置导航 AppRoute / GkAppNavigation / GkScaffold
                │
        li.gkd.app.sensorguard（状态机 + 配置）
                │
        SensorShell.freeze() / restore()
```

## 3. 数据模型（新增）

```kotlin
@Serializable
data class SensorGuardConfig(
    val enabled: Boolean = false,
    val rules: List<AppRule> = emptyList(),
    val defaultFreezeMs: Long = 5000,
    val coldStartOnly: Boolean = false,
    val showOngoingNotification: Boolean = true,
)

@Serializable
data class AppRule(
    val pkg: String,
    val freezeMs: Long? = null,
    val releaseOnActivityChange: Boolean = true,
    val splashActivities: List<String> = emptyList(),
)
```

持久化：独立文件（建议 `sensor_guard.json` + `sensor_guard_frozen_until.txt`），用 `FileSettingsStorage(appStorage().store)` + `kotlinx.serialization.json.Json`（与 `SettingsRepository` 同风格：`ignoreUnknownKeys`、`encodeDefaults`）。**不要**塞进 `SettingsStore`，减少与上游合并冲突。

`frozenUntil: Long`：冻结时写入，恢复时清零；启动时过期则兜底 `restore`。

## 4. 状态机

```
        目标App成为前台                    超时 / 离开开屏页 / 离开App
 IDLE ─────────────────► FROZEN ───────────────────────────────► IDLE
   ▲                        │
   └── 兜底：服务销毁 / 息屏 / 启动 / 特权重连 / 手动按钮 ──┘
```

规则：

- **进入**：前台包名从「非目标」变为「目标」且为 IDLE → `freeze`，启动超时 Job。
- **目标 App 内切 Activity**：若配置了 `splashActivities` 且新 Activity 不在其中 → 提前 `restore`；否则等超时。
- **目标 → 另一个目标**：先 `restore` 再 `freeze`（或重置超时），保证只有一个超时 Job。
- **离开目标 App**：立即 `restore`。
- **超时**：`restore`。
- **防抖**：同一包 1.5 秒内不重复触发。
- **`restore()` 幂等且无条件执行**。

## 5. 核心骨架（按对照表调整）

```kotlin
// 包：li.gkd.app.sensorguard
object SensorGuard {
    private const val DUMMY_PKG = "com.sensorguard.none"
    // topFlow: AndroidForegroundState.foregroundRulesFlow
    //   .map { it.topActivity }.distinctUntilChanged()
    // TopActivity.appId / TopActivity.activityId
}

object SensorShell {
    // androidMain：经特权 UserService 执行 dumpsys
    suspend fun freeze(pkg: String): Boolean
    suspend fun restore(): Boolean
}
```

## 6. 兜底恢复

| 场景 | 真实接入点 |
|---|---|
| App/服务启动 | 应用启动路径调用 `SensorGuard` 启动兜底 `restore`；读 `frozenUntil` |
| 无障碍销毁 | `A11yService` 的 `lifecycleHooks.onDestroyed` / `onDestroy` / `onUnbind`（最小挂接） |
| 息屏 | 复用或旁路订阅 `Intent.ACTION_SCREEN_OFF`（见 `AndroidMatchObservers.initScreenEvents`） |
| 特权 / Shizuku 重连 | 观察 `privilegeContextFlow`：从非 null→null 或 null→非 null 时 `restore` |
| 冻结常驻通知 | 参考 `StatusService` 通知风格；受 `showOngoingNotification` 控制 |
| 快捷设置 Tile | 继承 `BaseTileService`（参考 `GkdTileService` 等） |
| 设置页手动恢复 | SensorGuard 设置页按钮 |
| 超时 Job | 主路径 |

## 7. UI

入口：设置相关页面新增入口（建议挂在 `SettingsPage`「其他」或 `AdvancedPage`），导航：

- 新增 `@Serializable data object SensorGuardRoute : AppRoute`
- 在 `GkAppNavigation` 注册页面
- UI：`GkScaffold` + `GkTopAppBar` + `GkTextSwitch` + `GkSettingItem`（对齐 `AdvancedPage` / `TouchOptimizationPage`）
- 添加 App：复用 `AppInfoRepository` + 白名单编辑风格（`AppWhitelistPage` / `AppIdListEditor` / `SettingsAppIds`）

## 8. 里程碑

### M0 勘察 ✅（本阶段）

产出真实符号对照表（见下节）。不写业务代码。

### M1 执行层

- 实现 `SensorShell`（需补 shell 执行能力，见对照表「决策点」）。
- 设置页临时测试按钮：冻结 5 秒再恢复。
- 验收：Sensor Box 数据停/恢复。

### M2 接入前台 Flow

- 订阅 `foregroundRulesFlow` → `TopActivity`，硬编码目标包，固定 5 秒。
- 验收：目标 App 开屏晃动不跳；5 秒后恢复。

### M3 状态机 + 全部兜底

- 状态机 + 第 6 节兜底；单元测试。
- 验收：强杀 / 杀特权 / 息屏后能恢复。

### M4 配置 + UI + 日志

- 配置持久化 + 完整设置 UI + 日志页。

### M5 收尾

- 自检、改包名评估、README；订阅规则仅评估不实现。

## 9. 手动测试清单

（同原方案：旋转/计步副作用、热启动、App 间切换、重启、真实摇一摇 App 对比。）

## 10. 风险与约束

- 冻结全局，默认窗口短（5s）。
- 白名单默认空。
- Shizuku / 特权服务重启后需重新激活，UI 必须提示。
- 厂商可能限制 `restrict` → M5 自检不能省。

---

## M0 真实符号对照表

| 方案中的 ⚠️ / 概念 | 仓库真实符号 | 位置 |
|---|---|---|
| TopState / 前台状态 | `TopActivity(appId, activityId, number)` | `gkd-app/.../rule/TopActivity.kt` |
| 前台 Flow | `AndroidForegroundState.foregroundRulesFlow: StateFlow<ForegroundRuleSnapshot>`，其 `topActivity`；另有 `observedForegroundFlow` / `topTaskAppIdFlow` | `gkd-app/.../matching/input/AndroidForegroundState.kt` |
| 当前前台快照 | `currentTopActivity` / `ForegroundRuleSnapshot.topActivity` | 同上 + `ForegroundRuleSnapshot.kt` |
| 更新前台 | `updateTopActivity(...)` / `ActivityScene` | `AndroidForegroundState.kt` |
| Shizuku 集成 | `li.gkd.app.priv.shizuku.*`（`GkdShizukuExternalStartProvider`、`PrivilegeShizukuExternalStarter`）用于**启动特权服务**，不是通用 shell | `gkd-app/.../priv/shizuku/` |
| 特权上下文 | `privilegeContextFlow: StateFlow<PrivilegeContext?>` | `PrivilegeApi.kt` |
| UserService | `li.gkd.app.priv.UserService` 实现 `li.gkd.aidl.IUserService` | 截图 / Surface / 安装 + **`sensorRestrict` / `sensorEnable`**（`SensorServiceCommands`）；`restrict` 后独立 `sleep 5; enable` 子进程；`userServiceSpec.version = 11` |
| 设置存储 | `SettingsRepository` + 私有 `PersistedValue` + `FileSettingsStorage` + `kotlinx.serialization.json.Json`；主配置 `store.json` → `SettingsStore` | `settings/SettingsRepository.kt`、`FileSettingsStorage.kt`、`SettingsStore.kt` |
| 设置导航 | `AppRoute`（`@Serializable`）+ `GkAppNavigation` + `MainViewModel.navigator` | `ui/navigation/AppRoute.kt`、`GkAppNavigation.kt` |
| 设置 UI 风格 | `GkScaffold` / `GkTopAppBar` / `GkSettingItem` / `GkTextSwitch`；参考 `AdvancedPage`、`TouchOptimizationPage` | `ui/settings/`、`ui/component/` |
| 应用选择 | `AppInfoRepository` + `AppWhitelistPage` / `AppIdListEditor` / `SettingsAppIds` | `ui/settings/`、`app/AppInfoRepository` |
| 无障碍服务 | `A11yService`：`onServiceConnected` / `onDestroy`→`lifecycleHooks.dispatchDestroyed` / `onUnbind`；业务清理在 `lifecycleHooks.onDestroyed { ... }` | `service/A11yService.kt` |
| 息屏广播 | `initScreenEvents()` 注册 `ACTION_SCREEN_OFF` 等；`isInteractive` | `matching/input/AndroidMatchObservers.kt` |
| Tile | `BaseTileService` 子类（如 `GkdTileService`） | `service/` |
| 包前缀 | 公共根包 `li.gkd.app` → 新模块包 **`li.gkd.app.sensorguard`** | AGENTS.md |
| 编译任务 | `:gkd-android:assembleGkdDebug` / `assembleGkdRelease` | 非 `assembleDebug` |
| LICENSE | **GPL-3.0-only** | 根 `LICENSE` |

### 已确认决策

1. **Shell 路径：A 的收紧版** — 不写通用 `execShell`；`IUserService` 仅 `sensorRestrict(pkg)` / `sensorEnable()`；服务端写死命令、正则校验 pkg、`ProcessBuilder` 数组参数、3s 超时；`restrict` 后独立子进程 `sleep 5; enable`；`userServiceSpec.version` = **11**（升级后需重连特权 / 重启 Shizuku）。
2. **M1 入口**：设置 → 高级设置（工具）→「实验性：传感器防误触（测试）」；正式入口 M4 再定。
3. **前台源 / 配置备份**：仍按 M0 建议，M2/M4 再落地。

---

## 里程碑汇报记录

### M0（完成）

见对话汇报。

### M1（完成）— 见对话完整汇报

摘要：固定 AIDL `sensorRestrict`/`sensorEnable`；`SensorServiceCommands` + 独立 `sleep 5; enable` 子进程；`userServiceSpec.version = 11`；高级设置测试页；Debug HTTP + `.temp/sensorguard/m1-accept.sh` 自动验收 **PASS**（RESTRICTED → ~4s NORMAL，Sensor Box 在前台）。

### M2 / M2.5（完成）— 见对话完整汇报

- AIDL：`sensorRestrict(pkg, autoRestoreMs)` 限幅 [1000,30000]；`sensorCalibrate`；`userServiceSpec.version = 12`
- `AppRule` + `ON_FOREGROUND` 运行时（内存规则，默认目标 Sensor Box）；`/debug/sensor/target|calibrate|status`
- M2.5：PCHM10 dumpsys 可解析包前缀监听与传感器类型；校准路由可用
- 模式 B/C 仅数据预留，未实现；选择器引擎未改

### M2.6（完成）— 见对话完整汇报

- 补测：`foregroundRulesFlow` 无规则/`enableMatch=false`/`enableAutomator=false` 仍更新 top；双 restrict 互斥；强停 GKD / 杀 Shizuku 后 Mode 仍可回 NORMAL（`setsid nohup`）；延迟冷热约 120–250ms；Sensor Box 页 restrict 时 events 停、enable 后同页自动续
- 修正：`userServiceSpec.version = 13`；自动恢复子进程 `setsid nohup` + 单 `autoEnablePid`；包名匹配 `pkg` / `pkg.*`
