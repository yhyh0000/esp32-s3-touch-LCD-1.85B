# MOTO GPS Android（社区移植，开发中）

这是面向 **Waveshare ESP32-S3-Touch-LCD-1.85B** 的原生 Android 配套 App，目标测试机为
**vivo X200 Pro mini / Android 16**。代码位于本仓库的 `platforms/android/`，固件仍使用本仓库
现有的 1.85B 配置；没有要求设备重刷专用 Android 固件，也没有修改圆屏 v1 协议。

上游项目：[mx3353672833-debug/moto-gps-waveshare](https://github.com/mx3353672833-debug/moto-gps-waveshare)
；当前硬件基线：[tang003/esp32-s3-touch-LCD-1.85B](https://github.com/tang003/esp32-s3-touch-LCD-1.85B)。
这是社区 Android 移植，不代表上游已经提供或官方支持 Android App；欢迎按上游的共创说明反馈和回馈改动。

## 工具链基线

| 组件 | 版本 |
| --- | --- |
| JDK | 17 |
| Gradle Wrapper | 8.13 |
| Android Gradle Plugin | 8.7.3 |
| Kotlin | 2.0.21 |
| Jetpack Compose BOM | 2024.12.01 |
| compileSdk / targetSdk | 35 / 35 |
| minSdk | 26 |
| NDK | 27.0.12077973 |
| CMake | 3.22.1 |

Android 16 的真机行为仍以实测为准；targetSdk 35 不等于已经完成 Android 16 兼容验收。
本机当前没有可用的 JDK、Android SDK 或 Gradle 命令，因此第一阶段的 Android 构建由 GitHub
Actions 在干净环境执行；不要把这个限制写成“本地构建通过”。

## 已实现内容

- 原生 Kotlin + Jetpack Compose 工程骨架。
- NDK/CMake/JNI 直接编译并复用 `shared/ble_protocol` 和 `shared/coordinates`；Kotlin 不重复实现
  v1 帧头、CRC、分片、重组和 payload 布局。
- 使用现有 Navigation Service UUID 扫描 `MOTO GPS`，由用户明确选择设备后连接。
- GATT 服务发现、TX Notify/CCCD 订阅、MTU 请求（517）及按实际 MTU 计算 `max_frame_size`。
- GATT 写操作串行排队；握手帧使用 Write With Response，后续心跳可使用 Write Without Response。
- Android 系统配对/加密失败时保留固件加密权限，进入配对流程后重新发现服务，不通过关闭加密绕过。
- v1 `Phone Starting → Device Ready → Phone Ready → Device Ready` 完整握手。
- 以当前 BLE 会话的已用时间发送心跳，不把手机系统启动时长写入协议。
- App 内“协议自检”：复用共享 C++ codec 对已有 golden payload/frame 和坐标核心进行检查。
- 网关地址可配置；目的地搜索、WGS84 位置偏置、候选路线返回和路线选择已接入现有
  `/v1/places`、`/v1/route-options` schema。
- 选定路线后，手机使用共享坐标核心把 WGS84 位置转换为 GCJ-02，并通过共享 BLE codec
  发送有边界的 RouteGeometry 窗口与 NavigationSnapshot；没有位置或网关失败时不自动伪造位置/路线。
- 仍不宣称已经完成高德实车导航：当前实现尚未包含前台导航服务、锁屏保活、自动重算路线、离线地图、
  路况数据或音乐控制。

## 真实接口基线

- 服务：`7e57a000-b50c-4b6a-9c57-40a54e8e1000`
- 手机写入 RX：`7e57a001-b50c-4b6a-9c57-40a54e8e1000`
- 设备通知 TX：`7e57a002-b50c-4b6a-9c57-40a54e8e1000`
- CCCD：标准 `00002902-0000-1000-8000-00805f9b34fb`
- 广播名：`MOTO GPS`
- BLE v1 帧：固定头 + 每分片 CRC16-CCITT-FALSE；协议逻辑分片不可交织。
- 1.85B 设备能力包含导航、路线几何、路况、MapScene、媒体/触摸命令和设备设置；Android 首阶段
  只建立会话，不宣称这些业务已经接通。

坐标边界保持上游约定：网关路线输入位置为 WGS84，路线几何和 MapScene 为 GCJ-02；地图瓦片编号
仍按 WGS84 Web Mercator。Android 不在 BLE 层重复转换坐标。

## 构建与检查

在 `platforms/android/`：

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Windows PowerShell：

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

CI 会从干净检出拉取子模块，并安装固定的 SDK、NDK 和 CMake 后执行相同的 Gradle 检查。

当前提交 `712879d` 的 [Android app checks](https://github.com/yhyh0000/esp32-s3-touch-LCD-1.85B/actions/runs/38144198869)
和 [Source checks](https://github.com/yhyh0000/esp32-s3-touch-LCD-1.85B/actions/runs/38144198870)
均已通过。Android 检查包含干净 CI 环境中的 JNI/CMake、单元测试、lint 和 Debug APK 打包；
这不等于真实手机、网关或圆屏导航验收。该运行的 Debug APK 制品名为
`moto-gps-android-debug-712879dcf139fe80496ac30cefff94a91920831c`。

## BLE 故障日志

当前 BLE 稳定性修复会输出以下可对时日志：

- Android `MotoBleGatt`：连接状态、服务/特征、MTU、CCCD、连接参数、每个 GATT 操作的队列序号、立即返回码、回调返回码、耗时、队列长度和超时。
- Android `MotoBleSession`：会话 ID、握手状态、设备事件、设备心跳静默时长和手机发送心跳。
- ESP32 `moto_ble`：connection epoch、连接句柄、加密/Notify 订阅状态、ATT MTU、手机 RX 帧、设备 TX Notify、Notify 返回码、RX 队列溢出和手机心跳 watchdog。
- ESP32 `board_1_85b`：CST816S I²C 错误按 1 秒节流，但保留累计次数，避免触摸总线错误淹没 BLE 时间线。

真机连接后可用 ADB 保存 Android 侧日志：

```powershell
adb logcat -c
adb logcat -v threadtime MotoBleGatt:I MotoBleSession:I BluetoothGatt:D *:S > vivo-moto-ble.log
```

最新测试 APK 还提供了“BLE 诊断日志”面板。测试一轮前先点“清空”，然后扫描、连接，
等板子状态变化或断开后点“复制日志”，把剪贴板全文发回。面板保留最近 500 条，包含本地
时间、`elapsedRealtime`、GATT 状态、服务发现、MTU、CCCD、通知、握手、ACK、队列和超时；
同一批事件仍会写入 `MotoBleGatt` / `MotoBleSession` Logcat。日志只保存在当前 App 进程内，
不上传服务器。

板子串口日志需与 Android 日志使用同一轮测试编号对齐；重点搜索 `GATT op timeout`、
`device heartbeat timeout`、`phone heartbeat timeout`、`notification failed`、`phone disconnected`。
Android 心跳已改为 Write With Response，目的是让 vivo 的手机→板子写入卡死能被操作超时检测到，
而不是让队列永久保持 READY。超时后会清理 GATT、结束会话并等待重新连接。

## 权限与未验证项

第一阶段申请 Android 12+ 的 `BLUETOOTH_SCAN` / `BLUETOOTH_CONNECT`，旧系统申请精确定位用于
BLE 扫描，并申请 Android 13+ 通知权限；真实导航还申请精确/粗略位置权限。没有使用
`neverForLocation`，因为导航定位是独立能力。当前定位仍由 Activity 进程内的 `LocationManager`
维护，前台导航服务、持续通知、锁屏行为和通知停止入口尚未加入；不声明“后台不被杀”或“锁屏可用”。

BLE 连接/握手已在 vivo X200 Pro mini / Android 16 与 1.85B 真机联调过；本次真实网关路线、
持续定位、锁屏、断连恢复、MTU 边界和耗电仍未完成验收。没有把 CI 通过标记为硬件验收完成。

## 阶段进度

| 阶段 | 状态 |
| --- | --- |
| 工程 / JNI / BLE / 协议自检 | 已实现，CI 构建通过，BLE/协议已做真机联调 |
| 搜索、WGS84 定位、候选路线、导航快照 | 已实现第一版，待真实网关和连续导航验收 |
| 前台导航服务、锁屏与断连恢复回归 | 未开始 |
| 在线 / 城市 / 沿途地图下载 | 未开始 |
| 音乐控制 | 后续评估，不承诺任意音乐 App 可控 |
