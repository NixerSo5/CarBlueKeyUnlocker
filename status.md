# 项目状态 / Status

> 更新日期：2026-11-25
> 当前分支：`rongwei`（上汽荣威专用）
> 当前版本：`1.2`（`versionCode=12`）
> 项目路径：`C:\Users\nixer\Desktop\car\car-blue-key-unlocker`

---

## 1. 项目概况

Android BLE 蓝牙钥匙助手。BLE 只负责**检测车辆**，真正的目标是：**目标 App 进程死了才静默拉起，进程活着就不打扰**。

本分支是上汽荣威专用版：

| 项目 | 值 |
|---|---|
| 应用名称 | 07电子钥匙助手 |
| 拉起目标 | 固定 `com.saicmotor.tocapp`（上汽荣威） |
| 应用选择 | ❌ 无，不提供“选择要拉起的应用” |
| 目标设备 | `SMPLSJEK4398TK065882` / `90:29:62:0C:9B:4D` |
| 默认挡位 | 非常远（约5-10米·推荐），`-75dBm` / 连续 2 次 |

> `master` 是通用版（可选应用、通用图标）；本 `rongwei` 分支保持上汽专用名称和图标。

---

## 2. Git 状态

```text
* rongwei  [origin/rongwei] 版本 1.2（静默保活 + 绑定守护服务判活）
  master   [origin/master]  未改动，未 push
```

- 远端：`origin` = https://gitee.com/nixer/car-blue-key-unlocker.git
- 本次只 push `rongwei`，**不改 master、不 push master**
- 旧 Tag：`v1.0.0-rongwei` 不包含本次静默保活逻辑
- 本地 Git 身份：`nixer <nixer456@163.com>`

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
- 绝不 `startActivity` 兜底，不弹上汽荣威界面
- 不会把自己的界面切回前台（已删掉 `bringOurTaskToFront()`）
- `RELAUNCH_GUARD_MS = 3_000`，只防同一瞬间重复触发；无 90 秒冷却
- 旧 `isAppRunning()` 判据已不再使用：Android 16 上只能看到自身进程，会误判

### 主界面

- 显示：目标设备/MAC、目标App进程、守护服务、信号强度、距离挡位、最近发现、服务启动时间、看门狗
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
$env:JAVA_HOME='C:\Users\nixer\AppData\Local\Temp\opencode\jdk\jdk-17.0.21.1+2'
$env:ANDROID_HOME='C:\Users\nixer\AppData\Local\Temp\opencode\android-sdk'
$env:ANDROID_SDK_ROOT=$env:ANDROID_HOME
.\gradlew.bat assembleDebug --no-daemon
```

- `compileSdk 35` / `minSdk 26` / `targetSdk 35`
- 最近构建：`BUILD SUCCESSFUL`
- APK：`app\build\outputs\apk\debug\app-debug.apk`
- ADB：`C:\Users\nixer\AppData\Local\Temp\opencode\android-sdk\platform-tools\adb.exe`

---

## 6. 待办

1. push `rongwei` 到 `origin/rongwei`（版本 1.2）
2. 如需发布，再创建 `v1.2` Tag 和 Release（当前未创建）
3. 真车旁验证默认“非常远”挡位的触发距离和连接时间
