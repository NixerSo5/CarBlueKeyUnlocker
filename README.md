# CarBlueKeyUnlocker / 蓝牙钥匙助手

靠近车辆时自动识别蓝牙设备并拉起指定 App，无需 Root、无需 MacroDroid。

Automatically detect a nearby Bluetooth device and launch a chosen app when you approach your car — no root, no MacroDroid required.

---

## 中文

### 分支

| 分支 | 说明 |
| --- | --- |
| `master` | 通用版。应用名「蓝牙钥匙助手」，可自由选择要拉起的应用。 |
| `rongwei` | 上汽荣威定制版。应用名「07电子钥匙助手」，使用上汽图标，固定拉起 `com.saicmotor.tocapp`。 |

两个分支使用相同的包名 `com.nixer.carunlocker`，不能同时安装；切换分支后需重新构建并安装。

### 功能

- 选择目标蓝牙设备：扫描附近 BLE 设备并保存 MAC / 名称
- 距离灵敏度：4 挡滑块（很近 / 近 / 远 / 自定义），自定义挡可手动设置进入阈值、离开阈值、连续确认次数
- 保活：前台服务 + 看门狗服务 + AlarmManager + 开机自启 + `START_STICKY`
- 权限：启动时一次性申请定位、蓝牙、通知、电池优化白名单、悬浮窗权限
- 状态显示：守护服务 / 看门狗运行状态、已发现设备数、当前信号强度、最近发现目标、拉起应用、服务启动时间
- 文件日志：`/sdcard/Android/data/com.nixer.carunlocker/files/carguard.log`（部分 ROM 的 `logcat` 不可用）

### 触发逻辑

1. 扫描结果匹配用户选择的目标设备（MAC 或名称）
2. RSSI ≥ 进入阈值（默认 `-60dBm`）
3. 连续 N 次确认（默认 2 次，采样间隔 800ms，避免瞬时抖动）
4. 距离上次拉起超过 90 秒冷却时间
5. RSSI 低于离开阈值（默认 `-75dBm`）时重置计数，形成迟滞防抖

### 构建

- Android Studio 或命令行 Gradle Wrapper
- AGP `8.7.3`，Gradle `8.13`，`compileSdk 35`，`minSdk 26`，`targetSdk 35`
- Material Components `1.12.0`

```powershell
.\gradlew.bat assembleDebug
```

APK 输出：`app\build\outputs\apk\debug\app-debug.apk`

### 使用

1. 安装 APK并授予定位、蓝牙、通知权限
2. 在系统中允许自启动、忽略电池优化（部分国产 ROM 必须手动设置，否则进程会被杀）
3. 打开 App → 「选择蓝牙设备」→ 选中车辆蓝牙
4. （`master` 分支）点「选择要拉起的应用」→ 搜索并选择目标 App
5. 按需调整「距离灵敏度」
6. 保持「守护服务 ● 运行中」即可

> 该 ROM 上 BLE 首次扫描常无回调，代码中在启动 300ms 后重复调用一次 `startScan()`（不 `stopScan()`）；日志中的 `scan failed, code=1` 可忽略。

### 免责声明

仅供个人学习与便利使用，请遵守当地法律法规及车辆厂商条款。作者不对误触发、车辆状态变化或任何损失负责。

---

## English

### Branches

| Branch | Description |
| --- | --- |
| `master` | Generic build. Named "蓝牙钥匙助手" (Bluetooth Key Assistant); the app to launch is fully user-selectable. |
| `rongwei` | SAIC-specific build. Named "07电子钥匙助手", uses the SAIC icon, and always launches `com.saicmotor.tocapp`. |

Both branches share the package name `com.nixer.carunlocker`, so they cannot be installed side by side. Rebuild and reinstall after switching branches.

### Features

- Target device selection: scan nearby BLE devices and store the chosen MAC address / name
- Distance sensitivity: 4-step slider (Very close / Close / Far / Custom); the custom step allows manual enter/exit RSSI thresholds and confirmation count
- Keep-alive: foreground service + watchdog service + AlarmManager + boot receiver + `START_STICKY`
- Permissions: one-time requests for location, Bluetooth, notifications, battery-optimization exemption, and overlay
- Status panel: service/watchdog state, discovered device count, live RSSI, last seen target, launch app, service start time
- File logging: `/sdcard/Android/data/com.nixer.carunlocker/files/carguard.log` (some ROMs have unusable `logcat`)

### Trigger logic

1. A scan result matches the selected target device (MAC or name)
2. RSSI ≥ enter threshold (default `-60dBm`)
3. N consecutive confirmations (default 2, sampled 800ms apart to avoid flapping)
4. More than 90 seconds since the last launch (cooldown)
5. RSSI below the exit threshold (default `-75dBm`) resets the counter, providing hysteresis

### Build

- Android Studio or the Gradle wrapper
- AGP `8.7.3`, Gradle `8.13`, `compileSdk 35`, `minSdk 26`, `targetSdk 35`
- Material Components `1.12.0`

```powershell
.\gradlew.bat assembleDebug
```

APK output: `app\build\outputs\apk\debug\app-debug.apk`

### Usage

1. Install the APK and grant location, Bluetooth, and notification permissions
2. Enable auto-start and battery-optimization exemption in system settings (required on many Chinese ROMs, otherwise the process will be killed)
3. Open the app → "选择蓝牙设备" (Select Bluetooth device) → pick your vehicle's Bluetooth
4. (`master` only) Tap "选择要拉起的应用" (Select app to launch) → search and pick the target app
5. Adjust "距离灵敏度" (Distance sensitivity) as needed
6. Keep the guard service "● 运行中" (Running)

> On the target ROM the first BLE scan often produces no callbacks, so the code calls `startScan()` again 300ms later without calling `stopScan()`. `scan failed, code=1` in the log is harmless.

### Disclaimer

For personal learning and convenience only. Comply with local laws and vehicle manufacturer terms. The author is not responsible for accidental triggers, vehicle state changes, or any damages.
