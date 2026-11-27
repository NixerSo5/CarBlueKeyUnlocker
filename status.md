# 项目状态 / Status

> 更新日期：2026-11-26
> 当前分支：`rongweiWZ`（上汽荣威 · 无障碍自动进数字钥匙页）
> 当前版本：`1.4`（`versionCode=14`）
> 项目路径：`C:\Users\nixer\Desktop\CarUnlocker`

---

## 1. 项目概况

Android BLE 蓝牙钥匙助手。BLE 只负责**检测车辆**，真正的目标是：**目标 App 进程死了才静默拉起，进程活着就不打扰**。

本分支是上汽荣威专用版：

| 项目 | 值 |
|---|---|
| 应用名称 | 07电子钥匙助手 |
| 拉起目标 | 固定 `com.saicmotor.tocapp`（上汽荣威） |
| 应用选择 | ❌ 无，不提供“选择要拉起的应用” |
| 目标设备 | 由用户在 App 内扫描选择（VIN / MAC 不写入仓库） |
| 默认挡位 | 非常远（约5-10米·推荐），`-75dBm` / 连续 2 次 |

> `master` 是通用版（可选应用、通用图标）；本 `rongwei` 分支保持上汽专用名称和图标。

---

## 2. Git 状态

```text
* rongweiWZ  （新分支）版本 1.4：靠近车辆 -> 无障碍自动打开上汽数字钥匙页
  rongwei   [gitee/rongwei] [origin/rongwei] 版本 1.4（已合并 rongweiWZ）
  master    [gitee/master] [origin/master]   未改动，未 push
```

- 远端：`gitee` = https://gitee.com/nixer/car-blue-key-unlocker.git
- 远端：`origin` = https://github.com/NixerSo5/CarBlueKeyUnlocker.git
- Tag `v1.4` 已推送到 gitee + github
- Release（含 APK + 使用说明）：
  - Gitee: https://gitee.com/nixer/car-blue-key-unlocker/releases/tag/v1.4 （id `1371097`）
  - GitHub: https://github.com/NixerSo5/CarBlueKeyUnlocker/releases/tag/v1.4 （id `459131842`）
- APK SHA256：`BB29BDDF48759FE5180C2FB546E7126F7B465AF62AB4CDE2E9E1B4B3CFADBB3F`

---

## 2.1 v1.4 关键结论（实测）

上汽 App 的解锁链路只有一条：打开“数字钥匙”页 → `DigitalKeyManager.connect()` → `BleManager.connect(vin)` → Zone 扫车 → GATT 连接 → 约 5 秒解锁。

实测排除的入口：

| 入口 | 结果 |
|---|---|
| 桌面小组件 `MiddleAppWidget` / `SmallAppWidget` | `onUpdate` 只画界面，无任何 BLE 调用 |
| deep link `smartsaic://roewe/digitalkey` | 冷/热启动都只落到 App 首页 `RWHomeActivity` |
| 直接 `am start` 数字钥匙页 | Android 16 连 shell 都不允许启动未导出 Activity |
| App 首页 | `Number of Ble app registered: 0`，不会启动 BLE 扫描 |
| UAES `HonorPullLiveService1` | 这台机器 UAES 栈未初始化，路径无效（v1.3 无效的原因） |

唯一可行：无障碍服务打开上汽首页 → 点击“数字钥匙” → 页面自动连接。
已验证节点：首页 `text="数字钥匙"`，可点击父节点为 `RelativeLayout [30,1386][558,1608]`。

---

## 3. 当前核心运行逻辑

### BLE 扫描

- `startScan()` 后不调 `stopScan()`，仅在 `onDestroy()` 停止
- 300ms 后再调一次 `startScan()`，兼容该 ROM 首次无回调问题
- `scan failed, code=1` = `SCAN_FAILED_ALREADY_STARTED`，日志显示 `scan already active (code=1), ignored`
- 60 秒没有任何广播则 `restartScan()`，避免“扫描中假活”
- `SAMPLE_INTERVAL_MS = 800ms`
- 目标广播消失超过 10 秒重置连续计数；**不再用弱信号判断离开**

### 距离挡位

4 挡：非常远 / 远 / 近 / 自定义。

```java
MODE_NAMES = {"非常远（约5-10米·推荐）", "远（约3-5米）", "近（约2米）", "自定义"}
MODE_ENTER = {-75, -68, -60, -75}
MODE_TIMES = {2, 2, 2, 2}
```

UI 已删除“离开阈值”输入框。

### 静默保活与拉起

- `KEEPALIVE_INTERVAL_MS = 30_000`：独立轮询，不依赖 BLE 是否命中
- BLE 命中只作为提前触发
- 拉起方式：`bindService()` 绑定目标 App 已导出的守护服务
  - 组件：`com.saicmotor.tocapp/com.uaes.key.core.daemon.huawei.BleICCEService`
  - `Context.BIND_AUTO_CREATE`
  - 绑定成功 = 目标进程一定存活
  - `onServiceDisconnected` = 进程不存活，下一轮保活重新绑定
- `com.zone.tsp.ble.core.DigitalKeyService` 未导出，由目标 App 自己启动
- 新增静默 SDK 初始化触发（一次性 + 4 秒后补一次扫描）：
  - `startService()` → `com.saicmotor.tocapp/com.uaes.key.core.daemon.honor.HonorPullLiveService1`
  - extras：`BleParamsOrigin="CarGuard"`、`keyId="carguard"`、`mac=目标车辆MAC（为空时用占位符）`
  - 该服务 `onStartCommand` 会调 `DigitalKeySDK.initService()`，使 `initSuccess=true`；补发第二次用于 `mService` 绑定后启动 BLE 扫描
  - 仍不 `startActivity`，不弹上汽荣威 Activity；只可能出现上汽前台服务通知
- 绝不 `startActivity` 兜底，不弹上汽荣威界面（v1.4 的“自动进数字钥匙页”是独立的无障碍流程，见下）
- 不会把自己的界面切回前台（已删掉 `bringOurTaskToFront()`）
- `RELAUNCH_GUARD_MS = 3_000`，只防同一瞬间重复触发；无 90 秒冷却
- 旧 `isAppRunning()` 判据已不再使用：Android 16 上只能看到自身进程，会误判

### 主界面

- 显示：目标设备/MAC、目标App进程、守护服务、信号强度、距离挡位、最近发现、服务启动时间、看门狗
- 显示：数字钥匙自动打开 / 无障碍状态
- 新增按钮：无障碍（点击去开启/管理；长按立即测试一次）
- 新增勾选：靠近时点亮屏幕（默认关闭）
- `目标App进程` 一行：
  - 绿色 `● 运行中`：守护服务已绑定
  - 灰色 `○ 未运行`：未检测到绑定
- 状态来源：`AppPrefs.K_TARGET_ALIVE`，由 `CarService` 连接回调写入
- 每 1.5 秒刷新

### 通知

- 通道：`car_guard_silent`
- `IMPORTANCE_MIN`、无声音、无震动、无角标、`VISIBILITY_SECRET`
- 旧通道 `car_guard` 会被删除
- 前台服务通知无法完全去掉，但只保留静默常驻，不弹横幅

### 扫描日志弹窗

- 右上角“扫描日志”
- 最新日志在最上方
- 自动刷新 1.5 秒可勾选
- 日志可勾选复制
- 绿色：目标信号 / TARGET NEAR / KEEPALIVE / bound daemon / target daemon connected 等
- 红色：failed / error / 被系统拦截等

日志文件：`/sdcard/Android/data/com.nixer.carunlocker/files/carguard.log`

---

## 3.1 v1.4 无障碍自动打开数字钥匙页

新增 `SaicKeyAccessibilityService`（`app/src/main/java/com/nixer/carunlocker/SaicKeyAccessibilityService.java`），配置 `res/xml/accessibility_service_config.xml`，Manifest 中声明 `BIND_ACCESSIBILITY_SERVICE`。

触发链：

```text
CarService.onScanResult 命中目标
  -> maybeTriggerDigitalKey()
  -> SaicKeyAccessibilityService.trigger()
  -> 启动上汽 App（getLaunchIntentForPackage，导出入口）
  -> 2.5s 后轮询根节点
     - 若在数字钥匙页（tv_digitalkey_state 或“手机钥匙+近车自动解锁”）直接等待
     - 若在首页：找 text="数字钥匙"，点可点击父节点；失败则手势点中心
     - 若顶层是 com.android.systemui（锁屏）：等待 12 次后放弃并写日志
  -> 打开后等 12s，读取 tv_digitalkey_state 写日志
  -> 不退出页面（避免断开 BLE）
```

参数：

- `DK_TRIGGER_COOLDOWN_MS = 90_000`
- `K_AUTO_OPEN_DK` 默认 `"1"`
- `K_WAKE_SCREEN` 默认 `"0"`（可选：靠近时 WakeLock 点亮屏幕 8 秒）

实测（nubia NX769J / Android 16，桌面解锁状态）：

```text
ACC: 开始流程 手动测试
ACC: 已启动上汽App
ACC: 点击“数字钥匙” 成功
ACC: 数字钥匙页已打开（已在数字钥匙页），等待蓝牙连接
ACC: 数字钥匙状态 = 未连接
```

冷启动（先 force-stop 上汽 App）同样能点进 `DigitalKeyDetailActivity`。

**已知限制**：锁屏/密码锁时顶层是 `com.android.systemui`，无障碍读不到上汽界面，无法点击；需要先解锁（或依赖上次停留在数字钥匙页）。

---

## 4. 真机验证结果

测试机：nubia NX769J / Android 16 / SDK 36 / 序列号 `912425710472`

核心逻辑实测（已验证同套静默保活代码）：

```text
KEEPALIVE -> bound daemon silently: com.uaes.key.core.daemon.huawei.BleICCEService
target daemon connected -> process alive: com.uaes.key.core.daemon.huawei.BleICCEService
```

杀掉目标进程后：

```text
target daemon disconnected -> process not alive
KEEPALIVE: 目标守护未连接，准备拉起
KEEPALIVE -> bound daemon silently: com.uaes.key.core.daemon.huawei.BleICCEService
target daemon connected -> process alive
```

随后确认：

- `com.saicmotor.tocapp` 进程恢复
- `com.zone.tsp.ble.core.DigitalKeyService` 正在运行
- 前台焦点仍是用户正在用的 App，没有弹上汽荣威，也没有弹本 App

努比亚/ZTE 需手动给「蓝牙钥匙助手」开启**关联启动**；未开启时会看到红色 `Blocked by AutoLaunch` 日志。该开关是 ROM 私有设置，不能用标准 `pm grant` 授予。

---

## 5. 构建

```powershell
$env:JAVA_HOME='C:\Users\nixer\.jdkvm\v21.0.3+9'
.\gradlew.bat assembleDebug --no-daemon
```

- `compileSdk 35` / `minSdk 26` / `targetSdk 35`
- 最近构建：`BUILD SUCCESSFUL`
- APK：`app\build\outputs\apk\debug\app-debug.apk`
- ADB：`C:\Users\nixer\AppData\Local\Temp\opencode\android-sdk\platform-tools\adb.exe`

---

## 6. 待办

1. 真车旁验证：解锁状态下靠近车辆，无障碍自动进数字钥匙页后是否 5 秒内解锁
2. 真车旁验证：锁屏状态下触发，上汽 App 是否能在后台完成连接（不依赖点击）
3. 确认锁屏时是否需要保留“点亮屏幕”选项，或改为引导用户解锁
4. 如果实测锁屏不可用，评估：保持上汽任务停在数字钥匙页 + 只拉起 App 的方案
5. ~~发布 v1.4 到 Gitee / GitHub~~ ✅ 已完成（tag `v1.4`，两个 Release 均含 APK + 使用说明）
6. 真车实测后按结果决定 v1.4.1 / v1.5 方向
