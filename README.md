# CarBlueKeyUnlocker · 上汽荣威定制版 2.0

靠近车辆后由手机直接通过 BLE 完成认证解锁，不依赖上汽荣威 App。车辆钥匙只保存在手机本地。

## 分支与版本

| 分支 | 版本 | 说明 |
| --- | --- | --- |
| `master` | 1.0 通用版 | 靠近目标蓝牙设备时拉起指定 App，可自由选择目标应用 |
| `rongwei` | 2.0 上汽荣威定制版 | 本分支。本机取钥匙 + BLE 直连认证解锁 |

Gitee 只保留 1.0 源码和 1.0 / 2.0 的安装包；2.0 的源码和说明以 GitHub `rongwei` 分支为准。

## 哪些是自动获取的

这些不需要提前写进配置，运行时会自动拿到：

| 内容 | 来源 |
| --- | --- |
| 手机号 | 用户在登录框输入 |
| 短信验证码 | 用户输入 |
| zone token / zone userId | 登录接口返回 |
| keyId | 申请钥匙接口返回 |
| 车辆 BLE 钥匙 `ks / kr` | 下载钥匙接口返回并解密 |
| 车辆 BLE MAC / 名称 | 钥匙 JSON 返回，或在 App 内扫描选择 |

## 哪些需要自己查找或填写

构建前在项目根目录的 `local.properties` 中填写。该文件已被 git 忽略，**绝对不要提交**。

| 字段 | 说明 | 从哪里获取 |
| --- | --- | --- |
| `APP_KEY` | 荣威官方 App 的接口 appKey | 官方 App / 反编译 |
| `APP_SECRET` | 接口签名密钥 | 官方 App / 反编译 |
| `PKSK_HEX` | 下载钥匙时签名用的私钥 | 官方 App / 反编译 |
| `SAIC_UUID` | 登录设备 UUID | 官方 App 的设备信息 |
| `SAIC_DEVICE_ID` | 设备 ID，用于钥匙绑定 | 官方 App 的设备信息 |
| `SAIC_VIN` | 车架号 | 行驶证 / 车辆 |
| `SAIC_USER_ID` | 账号 ID，可选兜底 | 登录响应 |

`local.properties` 只写字段名，值由使用者自己填写：

```properties
sdk.dir=C\:\\Android\\Sdk
APP_KEY=
APP_SECRET=
PKSK_HEX=
SAIC_UUID=
SAIC_DEVICE_ID=
SAIC_VIN=
SAIC_USER_ID=
```

## 构建

带本机配置（可以短信登录取钥匙）：

```powershell
.\gradlew.bat assembleDebug
```

公开安全版（不读取 `local.properties` 里的任何敏感参数，产物不含登录信息）：

```powershell
.\gradlew.bat assembleDebug -PpublicBuild=true
```

> Release 中提供的 2.0 APK 使用 `-PpublicBuild=true` 构建，不包含 `APP_KEY / APP_SECRET / PKSK / SAIC_VIN / SAIC_DEVICE_ID` 等参数，因此公开版无法在本机重新登录取钥匙。需要使用完整取钥匙功能，请按上表配置后自行构建。

## 使用

1. 安装 APK，授予蓝牙、定位、通知权限。
2. 在「设置 → 登录并获取钥匙」中输入手机号和短信验证码（仅完整构建可用）。
3. 在设备列表中选择车辆蓝牙。
4. 保持守护服务运行，靠近车辆后自动解锁；也可以在主页手动解锁 / 关锁。

## 安全说明

- 不要提交 `local.properties`、`status.md`、`reverse/`、`dist/`。
- 车辆钥匙只保存在本机 SharedPreferences 中，不写入安装包。
- 公开版 APK 不包含任何账号、车辆或厂商接口参数。
- 本项目仅供个人学习与便利使用，请遵守当地法律法规及车辆厂商条款。

---

## English summary

- `master`: generic 1.0 build that launches a user-selected app near a BLE device.
- `rongwei`: SAIC Roewe 2.0 build with local key fetching and direct BLE unlock.
- Automatically obtained at runtime: phone/SMS code (user input), zone token, user ID, key ID, BLE key, BLE MAC.
- Must be provided in the untracked `local.properties` when building from source: `APP_KEY`, `APP_SECRET`, `PKSK_HEX`, `SAIC_UUID`, `SAIC_DEVICE_ID`, `SAIC_VIN`, optional `SAIC_USER_ID`.
- Public APKs are built with `-PpublicBuild=true` and contain none of the above secrets.
