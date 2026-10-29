# CODEBUDDY.md

This file provides guidance to CodeBuddy Code when working with code in this repository.

## 项目定位

Android 应用：让手机扮演 **CarPlay 发送端（iPhone 角色）**，把本机原生 **CarLife+ 引擎的 H.264 码流直通**给支持无线 CarPlay 的车机。画面不重编码。

- 单模块 Android 项目，包名 `io.github.shaonialife.carptocar`，**全 Kotlin**（无 Java 源文件）
- 前身是同作者的 `CarDeck`（MIT），继承其 iAP2 / AirPlay 协议层与工程骨架
- **已移除 Shizuku 依赖**（2026-10-24）：`ICarInput.aidl` 仅作类型占位，`service` 恒为 null
- 应用名刻意不含 "CarPlay" 等第三方商标

## 构建与测试

### 工具链（版本已固定，不要随意升级）

| 组件 | 版本 |
|---|---|
| AGP | 8.7.3 |
| Gradle | 8.10.2 |
| Kotlin | 2.0.21 |
| JDK | **17**（`sourceCompatibility`/`targetCompatibility` = `VERSION_17`，`jvmTarget = "17"`） |
| Spotless / ktlint | 7.2.1 / 1.3.1（`ktlint_official`） |
| compileSdk / targetSdk / minSdk | 34 / 34 / **31** |

### 常用命令

```bash
./gradlew assembleDebug            # debug 包 → app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleRelease     # 发布包（无 keystore.properties 时退回 debug 签名）
./gradlew testDebugUnitTest        # 全部单元测试（纯 JVM，JUnit4）
./gradlew :app:lintDebug            # lint（abortOnError=true，基线 app/lint-baseline.xml）
./gradlew spotlessApply            # ktlint 自动格式化
./gradlew spotlessCheck            # 仅校验
./scripts/check-no-vendor-artifacts.sh   # 合规守卫，0=干净
```

单测定位（JUnit4，无 Android 依赖，可直接按类名过滤）：

```bash
./gradlew testDebugUnitTest --tests "io.github.shaonialife.carptocar.iap2.Iap2SenderSessionTest"
./gradlew testDebugUnitTest --tests "*Iap2LinkEngineTest*"
```

单模块、**无 productFlavor**，variant 只有 `debug` / `release`。`debug` 仅加 `versionNameSuffix = "-debug"`，**不加 `applicationIdSuffix`**（Shizuku 授权按包名授予，改包名会要求重新授权）。`release` 明确**不做混淆**（`isMinifyEnabled = false`），因为依赖反射与自定义 AIDL。

### 本机（macOS，Apple Silicon）构建环境已就绪（2026-10-27 修复）

原仓库在 `gradle.properties` / `gradle-wrapper.properties` 硬编码 Windows 绝对路径、`.tools/` 未入库，导致 macOS 无法直接构建。已改为：

| 文件 | macOS 取值 |
|---|---|
| `gradle.properties` | `org.gradle.java.home=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home`（Homebrew openjdk@17，17.0.20.1） |
| `gradle/wrapper/gradle-wrapper.properties` | `distributionUrl=https://services.gradle.org/distributions/gradle-8.10.2-bin.zip` |
| `local.properties`（已生成，gitignore） | `sdk.dir=/Users/shiwei/Library/Android/sdk`（已装 platform-tools / platforms;android-34 / build-tools;34.0.0） |

运行 `./gradlew` 前建议先 `export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home`（wrapper 启动器本身也需 JDK 17，否则报 "Gradle requires Java 17"）。

`.git/hooks/` 只有 `.sample`——README 描述的 pre-commit 钩子（自动跑 spotless + lint）**未纳入仓库**，需自行安装。

`.tools/` 内是 Windows junction（指向 `../cardeck/.tools`），含 JDK 17 + SDK 34 + Gradle 8.10.2 + adb + `init-mirror.gradle`（腾讯云镜像，本机 `google()`/`mavenCentral()` 易超时）。

## 架构

### 源码布局

`app/src/main/java/io/github/shaonialife/carptocar/`（85 个文件，约 30k 行）

| 包 | 职责 | 关键类 |
|---|---|---|
| 根 | UI 入口 + 会话中枢 | `MainActivity`(2319行)、`SessionController`(object, 2632行)、`SenderApp` |
| `carlife` | **CarLife 劫持**：冒充车机接管本机 CarLife 引擎码流 | `CarLifeHijack`(object)、`CarLifeHuClient`、`CarLifeProtocol`(object)、`CarLifeAudioSink`、`H264Sps` |
| `iap2` | iAP2 链路层 + CSM + 发送端状态机 | `Iap2LinkEngine`、`Iap2SenderSession`(bring-up 主序列)、`Iap2Csm`、`Iap2MessageIds`、`ReceiverProfile` |
| `airplay` | AirPlay 会话：配对/控制/流/加密/Timing | `AirPlayClient`(2939行)、`PairingClients`、`AirPlayCrypto`、`BinaryPlist`、`Tlv8`、`ControlCipher`、`StreamFrameCipher`、`TimingServer`、`VirtualIap2Link` |
| `video` | H.264 推流 | `VideoSender`（`startPassthrough`/`submitH264`/`requestKeyFrame`/`seedCsd`） |
| `audio` | 采集→AAC/PCM→RTP/ChaCha20 | `AudioSender`、`AacLcEncoder`、`AudioProfile`、`AudioSyncPacket`、`AudioTuning` |
| `bt` | 蓝牙 RFCOMM 双向 | `Iap2BluetoothClient`(主动连车机)、`Iap2BluetoothServer`(等车机回连)、`BtSession`、`TargetDevicePrefs` |
| `net` | WiFi 入网 / mDNS / ctrl 广播 | `WifiCarPlayConnector`、`CarPlayServiceBrowser`、`CarPlayCtrlAdvertiser`、`EndpointHosts`、`AirPlayFeatures` |
| `input` | 触摸分流 + 消费键 | `TouchReports`(解析)、`TouchRouter`(路由)、`ConsumerKeys` |
| `launcher` | 隐形屏车机桌面（保留能力，默认不走） | `VirtualLauncher`、`DockActivity`、`GridActivity` |
| `capture` / `render` | 画面源抽象 / EGL 多屏合成 | `ScreenSource`、`HiddenDisplaySource`、`DualDisplayComposer` |
| `media` | 通知使用权→媒体会话 | `MediaListenerService`、`MediaSessionsState` |
| `service` | 前台保活 | `CarPlayService`（`connectedDevice|mediaProjection`） |
| `transport` | 传输抽象 | `DuplexStream`、`Iap2LinkChannel` |
| `ui` / `legal` / `util` | UI 组件 / 免责声明 / 工具 | `ScreenDimmer`、`DisclaimerContent`、`Bg`、`DebugFileLog`、`DeviceIdentity` |

### 数据流

```
荣耀 CarLife+ 引擎 (com.baidu.carlife.honor)   H.264 + PCM
   → CarLifeHijack / CarLifeHuClient   冒充车机连 127.0.0.1 七端口，旁路直取码流
   → VideoSender / AudioSender         AirPlay 视频/音频流 (ChaCha20-Poly1305)
   → 蓝牙 iAP2 控制通道 (RFCOMM) + WiFi  (加入车机热点)
   → 车机 CarPlay 接收端
```

### 协议分层（`docs/PROTOCOL-NOTES.md`）

```
AirPlay 主会话: RTSP 控制 + H.264 流 + HID
mDNS/Bonjour:   _airplay._tcp (车机) / _carplay-ctrl._tcp (手机)
iAP2 CSM:       0x4040 分帧 + TLV 参数体
iAP2 链路层:    FF55 SYN / FF5A 数据帧 + 校验和
承载:           蓝牙 RFCOMM (无线) / USB bulk (有线，已排除)
```

### 连接建立

入口 `MainActivity.proceedToConnect()` → `requireBluetoothMac()`（**蓝牙地址门禁，未填直接拦下**，1.0.7 新增）→ `ensureBluetoothReady()` → `startConnectionNow()` → `SessionController.startConnection()`

`startConnection` 做四件事：启动前台 `CarPlayService`；启动 `ctrlAdvertiser`（广播 `_carplay-ctrl._tcp`，需读真实蓝牙 MAC 故走后台线程）；`bluetoothClient.start()`（主动连车机，SDP `…deca-deafdecacaff`）；`bluetoothServer.start()`（等车机回连，`…deca-deafdecacafe`）。

iAP2 bring-up 序列（`Iap2SenderSession`）：

```
0x1D00→0x1D01→0x1D02                              识别
0xAA00→0xAA01→0xAA02→0xAA03→0xAA05                MFi（跳过证书/签名校验）
配件发 5 个订阅帧 + 0x6800 StartHID → 回 0x6801
0x5702→0x5703                                     热点 SSID/密码/信道
0x4300→0x4301                                     AirPlay 端口/公钥/无线参数
0x4E0D→0x4E0E                                     通知无线可用
```

端点交接 `SessionController.onCarPlayEndpoint`：冗余端点防护（推流中忽略重复 0x4301）→ 收 0x5703 后入车机热点（`WifiNetworkSpecifier` 应用专属网络，**不带 `NET_CAPABILITY_INTERNET`**）→ 重注册 ctrl 广播 + mDNS 发现 → 端点完整则直接配对，否则事件驱动等 4s ctrl 邀请 → `AirPlayFeatures.supportsPairing()` 决定 `skipPairing()`（pre-HomeKit 明文会话）还是 `runPairing()`；配对失败由纯函数 `PairingFallback` 决策（`PairingFallbackTest` 钉死）。

### CarLife 握手（缺一步都不出画面）

```
→ 98305 (协议3.0)   ← 65538 match=1
→ 98343 (统计信息)   ← 65563/65540/65574
→ 98307 (HU_INFO)    ← 65574 模块状态 → 回 98344 全部置 1
→ 98386 (特征配置)   主动推，不等手机
→ 98311 (视频编码初始化)
```

关键参数：98386 `HU_FEATURE_CONFIG_RESPONSE` 设 `CONNECT_TYPE=5`(WiFi)、`I_FRAME_INTERVAL=20`、`DISABLE_RESOLUTION_OPTION=1`、`MEDIA_LYRIC_SUPPORT=1`；98311 `CarlifeVideoEncoderInfo` 字段 1/2/3 = 视图宽/高/帧率（来自 AirPlay SETUP，非写死），字段 9 `needAdapterScreen = true`。

### 触摸回传

- 单指：`0x68002`(DOWN) / `0x68003`(UP) / `0x68004`(MOVE)
- **双指走复合动作通道 `0x68001`**（`CarlifeTouchAction{action,x,y}`，动作码带指针索引：第二指按下 = `5|(1<<8)` = **261**，抬起 = **262**）
- 收尾固定「先 `POINTER_UP(手指1)` 再 `ACTION_UP`」
- 坐标**原样透传**（引擎自己按容器尺寸换算），只做 `0..视图宽高` 边界钳位

## 三条不可从代码直接推出的事实

1. 画面源是荣耀车联创建的虚拟屏（`HonorAutoDisplay` 1920×1080 @ 20fps，`type=VIRTUAL`），**分辨率与帧率由荣耀侧决定**，CarLife 协议改不动。
2. 厂商 mix 层会覆盖编码尺寸——`VideoConfig` 声明**以车机画布为准**（`liveSize` 实时读 98311），SPS 解析值只作诊断。
3. **CarLife 引擎不能由 App 自动拉起**（自动拉起拿不到画面源，只会编码欢迎页），必须用户手动打开 CarLife+ 点「无线连接」。`CarLifeHijack.prewarm()` 只起守候线程，不拉起引擎。

## 代码约定

### 字符串：不用 string 资源

`res/values/strings.xml` **只有 3 条**（`app_name`、`app_subtitle`、`accessibility_description`）。所有 UI 文案**硬编码为 Kotlin 中文字面量**（主源码约 5975 行含中文）。改文案直接改 Kotlin，**不要新建 string 资源**。

例外：`legal/DisclaimerContent.kt` 刻意把正文集中在代码里（便于与版本号一起审阅改动），且**刻意不用 Markdown 记号**（`**`/`#` 会原样显示进 TextView）。

### 风格

- `.editorconfig`：`ktlint_official`，`max_line_length = 140`，LF，纯空格缩进（4 空格为主），`insert_final_newline`，`trim_trailing_whitespace`
- `.gitattributes`：`* text=auto eol=lf`，`*.bat` 强制 CRLF
- 显式导入，不折叠为通配导入
- 数字用下划线分隔：`1_000L`、`120_000L`
- 行尾逗号（ktlint_official 要求）
- 分区注释 `// ---- 名称 ----`（`.editorconfig` 已放开 `ktlint_standard_no-consecutive-comments`）

### KDoc 是本项目最强的风格特征

几乎每个类/函数都有中文 KDoc，且**记录「为什么这样写」而非「做了什么」**——含日期、实测机型、日志行、字节码证据、踩坑根因。**修改代码时必须延续这一密度与信息量**，不要写成流水账式注释。

### 架构偏好

- **纯函数优先**：协议编解码、坐标换算、策略判定都抽成 `internal`/私有纯函数，以便 JVM 单测（项目无 Robolectric）
- **失败即降级**：`SysShell` 无 shell 权限、`CarInputClient.service == null` 都走降级分支，不抛异常
- **跨线程状态**用 `@Volatile`；幂等门禁用 `AtomicBoolean`（如 `sessionStarting`）
- **短命后台任务**统一用 `Bg.run("name") { }`，不要裸 `new Thread`；长驻循环线程不走这里
- **日志节流**：`lastLogAt` + `SystemClock.uptimeMillis()`
- **统一日志出口**：`SessionController.appendLog()` 是唯一入口；`DebugFileLog` 落 `files/main_debug.log`（2MB 上限）
- 日志前缀为常量：`CARLIFE:` `VIDEO:` `AUDIO:` `BT-C:` `IAPLINK:` `SVC:` `MAC:` `EVENT:` `TOUCH:` `AIRPLAY:` `MEDIA:`

### 生命周期与线程要点

- `SessionController` 是**进程级 object**，会话管线与日志缓冲全在它身上；`MainActivity` 只是「挂屏」，销毁重建不影响会话，状态靠 1s 轮询（`heroTick`）对账
- `SenderApp` 安装全局 `UncaughtExceptionHandler`，**只记录不 kill 进程**（一个套接字异常不该毁掉整条链路）
- `CarPlayService` 必须用**两参** `startForeground(id, notification, type)` 显式指定 `FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE`；旧版两参会拾取 manifest 全部类型，Android 14 上崩溃
- MediaProjection：Android 14+ 必须先注册 `MediaProjection.Callback` 再 `createVirtualDisplay`
- `MainActivity` 手动处理 `configChanges`（旋转不重建）

### 版本号

**唯一来源**：`app/build.gradle.kts:16` 的 `appVersionName`（当前 `"1.0.7"`）。`versionCode` 由它**推导**（`major*10000 + minor*100 + patch` → 10007），**不要手填 versionCode**。

## 合规硬约束

改代码前先看 `scripts/check-no-vendor-artifacts.sh`，改完**必须跑一次**（0 = 干净）：

1. **不得入库**：反编译产物（`.dex/.smali/.so`）、厂商 SDK、非基础设施 `.jar`、签名材料（`keystore.properties`/`*.jks`/`*.keystore`）、`.tools/` 与 `reference/` 目录
2. **不得出现明文凭据**：代码与日志里写 `psk=***` / `psk=(空)`；禁止 `psk=<8位以上十六进制>` 与 `passphrase = "<8位以上>"`
3. **不得写真实车机标识**：文档与测试用占位值 `Audi_MMI_0000` / `AA:BB:CC:DD:EE:FF` / `DESKTOP-XXXXXXX`
4. **应用名不含第三方商标**：`CarPToCarL`，`app_name` 不含 "CarPlay"

三条铁律（源码 KDoc 反复强调）：① **按能力分支，不按版本号/品牌硬编码**（`ReceiverProfile`）；② **判定必须可解释**（`ReceiverProfile.describe` 把依据写进日志）；③ 改动后必跑合规脚本。

## 测试

纯 **JUnit 4**（`junit:junit:4.13.2`），**无 AndroidX Test / 无 Robolectric / 无 MockK**，**无 `androidTest/`** 目录。34 个测试文件、43 个测试类、201 个 `@Test`（约 4.2k 行）。

测试写法值得沿用：`Iap2LinkEngineTest` 让两个引擎实例**直接交换输出字节**完成握手（无线程、无 I/O）；`AudioSenderTest` 复刻接收端解密路径逐字节还原；`TouchReportsTest` 把**真实 HID 描述符字节**写进测试（奥迪 `eso_touchScreen` 62B）；`transport/InMemoryStreamPair` 为 `DuplexStream` 提供内存回环。

## 文档

`docs/` 9 篇，用 ✅/❓ 标注确认度：

- `PROTOCOL-NOTES.md` — iAP2 链路层/CSM/无线时序/mDNS/AirPlay 逐层协议依据
- `carlife-hijack-findings.md` — CarLife 逆向：七端口、消息号、帧格式、鉴权卡死点
- `carlife-hijack-integration.md` — 劫持集成实现说明
- `carlife-wireless-verification.md` — 单机回环 Go/No-Go 实验方案
- `audio-call-issues.md` — 音频握手与来电黑屏的真机日志分析
- `touch-multitouch.md` — 双指触控三层原因与改法
- `CARLIFE-GATEWAY-FEASIBILITY.md` / `WIRED-CARPLAY-FEASIBILITY.md` — 路线可行性调研
- `LEGAL-NOTES.md` — 开源/收费法律风险分层

**已知文档与代码不一致（以代码为准）**：

- `MANAGE_EXTERNAL_STORAGE` 权限仍在 manifest（README 1.0.3 称已随画质增强移除）
- 触摸消息 ID：`docs/carlife-hijack-integration.md` 与 README/`touch-multitouch.md` 冲突，以 README 为准（0x68002=DOWN）
- `docs/carlife-hijack-integration.md` 提到的 `SettingsFragments.kt`、`fragment_panel_display.xml`、Python 脚本均已不存在（车机设置模块 2026-10-24 移除）
- `CarLifeHijack` 顶部 KDoc 称「默认画面源回归自绘桌面」，与 README「自绘桌面已移除」矛盾，疑为过时残留
- 统计数字：README 称 83 个 Kotlin 文件 / 33 个测试文件，实测 85 / 34

## 改代码速查

```
改协议层    → iap2/(Iap2LinkEngine, Iap2Csm, Iap2SenderSession) + airplay/(AirPlayClient)
改画面通路  → carlife/CarLifeHijack + video/VideoSender
改音频      → audio/AudioSender + audio/AudioSyncPacket + carlife/CarLifeAudioSink
改连接流程  → SessionController
改 UI 文案  → 直接改 MainActivity.kt 里的中文字面量（不要建 string 资源）
改触摸      → input/TouchReports(解析) → input/TouchRouter(路由) → carlife/CarLifeHijack.handleTouch(回灌)
改协议常量  → iap2/Iap2MessageIds.kt + carlife/CarLifeProtocol.kt
改偏好      → launcher/settings/*Prefs.kt + bt/TargetDevicePrefs.kt + legal/DisclaimerPrefs.kt
加测试      → app/src/test/java/... 同名包下，纯 JUnit4，不依赖 Android
```
