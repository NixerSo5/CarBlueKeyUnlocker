# CarUnlocker

## 配置

```properties
sdk.dir=C\:\\Android\\Sdk
APP_KEY=
APP_SECRET=
PKSK_HEX=
BANMA_SIGN_KEY=

# 可选
SAIC_VIN=
SAIC_DEVICE_ID=
SAIC_UUID=
```

## 构建

```powershell
.\gradlew.bat :app:assembleDebug --no-daemon
.\gradlew.bat :app:assembleDebug --no-daemon -PpublicBuild=true
```

## 安装

```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

## 使用

1. 打开 App
2. 授予蓝牙、定位、通知、后台运行权限
3. 设置 → 登录并获取钥匙
4. 输入手机号和短信验证码
5. 选择车辆蓝牙设备
6. 开启守护服务
7. 靠近车辆自动解锁，离开车辆自动上锁
