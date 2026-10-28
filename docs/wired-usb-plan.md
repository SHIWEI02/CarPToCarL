# 有线 USB CarPlay 方案：实测进展 + Mac 操作手册

> 记录时间：2026-10-29
> 实测机型：小米 M610BB（HyperOS / Android 17，内核 6.18.21-android17 GKI），root 为
> **CVE-2026-64560 PoC 内存注入式 KernelSU**（硬重启失效，需重跑 PoC）
> PC 端已在 Windows 上跑通工具链；本手册以 **macOS 为主**（Mac 上条件比 Windows 好，见 §4.1）

---

## 0. 一句话现状

**手机端 configfs 全流程已打通，卡在最后一步「绑定 UDC」。**

```
OK  setenforce 0 / 停 USB HAL / 解绑 g1 / 建 gadget / 写描述符 / 挂 ncm.usb0 / 链接 config
FAIL echo UDC 失败: rc=1        ← 唯一失败点
```

门没死，但这是决定生死的 go/no-go 点：如果 errno 是 **EBUSY** 且绕不过，
说明内核/HAL 不允许 root 抢 UDC，方案 A（手机端原生 gadget）出局，转方案 B（外接硬件桥）。

---

## 1. 为什么这条路可能成立（推翻原可行性文档的判断）

仓库里 `WIRED-CARPLAY-FEASIBILITY.md` 当年判定「不可行」，它的前提是 **BL 锁死、无 root**。
现在有 root，前三道门全部推开：

| 原判断的阻碍 | 现状 |
|---|---|
| BL 锁死、无法改 gadget | ✅ root 可写 configfs |
| HAL 不提供 NCM / vendor function | ✅ 内核自带 `ncm`，实测可创建 |
| 无权限操作 UDC | ✅ `setenforce 0` 后可读写 configfs |
| 需要 MFi 芯片 | ⚠️ **不需要**，见下 |

### 关于 MFi：它不是障碍

iAP2 的认证（0xAA00 段）方向是 **配件（车机）向手机证明自己**，
**手机侧可以跳过校验**。我们不需要伪造 MFi 芯片，唯一拦路虎是 USB 枚举。

### 关键洞察：我们可以跳过 lockdown 三层

MacPlay 的有线实现走的是：

```
USB bulk → usbmux → lockdown 信任配对 → StartService(com.apple.carkit) → iAP2
```

它需要 lockdown，是因为对面是**真 iPhone**（必须信任配对）。
而我们的 device 是**自己的 Android，两端可控** → **直接在 USB bulk 上跑 iAP2**。

真实车机走的也是 iAP2 over bulk（lockdown 是 PC/Mac 工具才走的路），
所以只要伪装成 iPhone 枚举出来，车机照样认。

**这是整套方案工作量能收敛的根本原因。**

---

## 2. 已验证的事实（实证，不是推测）

### 2.1 手机端 USB gadget 能力（小米 M610BB 实测）

| 项 | 结果 |
|---|---|
| root | ✅ `uid=0`，上下文 `u:r:ksu:s0` |
| configfs | ✅ `/config/usb_gadget` 有 **g1（已绑 UDC）、g2（空闲）** |
| UDC | ✅ `a600000.dwc3`（**独占**） |
| 内核配置 | ✅ `CONFIG_USB_CONFIGFS=y`、`USB_F_NCM=y`、`USB_F_ECM=y`、**`USB_F_FS=y`** |
| FunctionFS | ✅ `/dev/usb-ffs/` 存在且系统在用（adb/aoa/mtp/ptp/ctrl/ipcr） |
| SELinux | ⚠️ Enforcing，必须 `setenforce 0` |

实测值：

```
g1/idVendor  = 0x2717        g1/idProduct = 0xff48
UDC          = a600000.dwc3  vendor.usb.controller = a600000.dwc3
sys.usb.config（默认） = adb
HAL 服务 = android.hardware.usb.gadget.IUsbGadget/default
```

### 2.2 真 iPhone 的 USB 描述符（黄金样本，gadget 的直接模板）

用 `carplay-probe 05ac` 在 Windows 上实测（PID **0x12a8**）：

```
config 3 (active)
  iface 0  class 06/01/01  PTP     ep 0x02 OUT / 0x81 IN (bulk 512), 0x83 IN (int 64)
  iface 1  class ff/fe/02  ← iAP2  ep 0x04 OUT / 0x85 IN (bulk 512)
config 4  ← CarPlay 配置
  iface 0  class 06/01/01  PTP
  iface 1  class ff/fe/02  ← iAP2  ep 0x04 OUT / 0x85 IN (bulk 512)
  iface 2  class ff/fd/01  ← NCM 网络口
      alt 0: 无端点
      alt 1: ep 0x86 IN / 0x05 OUT (bulk 512)
      alt 2: ep 0x86 IN / 0x05 OUT (bulk 512)
```

**要记住的两个特征：**

- iAP2 over USB 接口 = **class `0xff` / subclass `0xfe` / protocol `0x02`**
- CarPlay 配置多一个 NCM 网口 = **class `0xff` / subclass `0xfd` / protocol `0x01`**，
  **alt 0 无端点、alt 1 起才有 bulk**

端点号不是固定的，应从描述符读，别硬编码。

### 2.3 Windows 上试过的两条 iPhone 路（都堵死，Mac 上不同）

| 路 | Windows 结果 | Mac 预期 |
|---|---|---|
| usbmux（`127.0.0.1:27015`） | ❌ Apple Mobile Device Service **拒绝非 Apple 客户端**（ReadBUID/Listen 均 RST） | ✅ **macOS 有系统 `usbmuxd`**，MacPlay 直接可用 |
| 直接 USB claim | ❌ `MI_01` 是 Apple 官方驱动（Apple, Inc. 538.0.0.0），标着 `WINUSB` 但 error 50 不让第三方访问 | ✅ Mac 上无 Apple 独占驱动问题 |

---

## 3. 当前卡点与排查优先级

日志 `/sdcard/gadget.log`（手机端，脚本每步落盘）：

```
OK   setenforce 0                                    -> Permissive
OK   setprop sys.usb.config none / sys.usb.state none
OK   解绑 /config/usb_gadget/g1/ (was a600000.dwc3)
OK   mkdir /config/usb_gadget/carplay
OK   idVendor / idProduct / bcdDevice / bcdUSB
OK   strings / configs / MaxPower
OK   function 实例名可用: ncm.usb0
OK   链接 function 到 config
--- 绑定 UDC ---
FAIL 写入 UDC 失败: rc=1
绑定后 carplay/UDC = []    VID=0x1209 PID=0x0001
dmesg 尾部：无 USB/gadget 相关错误
```

**排查顺序（errno 决定对策，三者完全不同）：**

1. **先把真 errno 打出来** —— 脚本里 `2>/dev/null` 把错误信息吞了，必须改成捕获 stderr
   （EBUSY / ENODEV / EINVAL 三种对策完全不同）
2. 解绑 g1 后 **sleep 3**（当前只 sleep 1），确认 `cat g1/UDC` 为空再绑
3. 高通 `msm-dwc3` 可能需要 pullup：dmesg 见过
   `msm-dwc3 a600000.ssusb: DWC3_CONTROLLER_PULLUP_ENTER/EXIT`
4. 试 `echo "" > g1/UDC` 后再绑 carplay，或反过来先绑再删 g1
5. 若始终 **EBUSY** → 判定内核/HAL 不让抢，转 §8 外接硬件桥

### ⚠️ 已确认的副作用（务必注意）

脚本会把 `persist.sys.usb.config` 设成 `none`。
**不恢复的话手机下次重启 USB 功能全废**（MTP / adb / 充电识别）。
已在 Windows 侧改回 `adb` 并验证。**Mac 上操作前请先记下原值**：

```bash
adb shell getprop persist.sys.usb.config    # 记下来
```

---

## 4. Mac 环境准备

### 4.1 为什么 Mac 比 Windows 更适合干这件事

1. **Rust 工具链原生**：不用折腾 MinGW / MSVC（Windows 上为了省 2GB 的 VS 走了 gnu + WinLibs）
2. **无 Apple 驱动独占问题**：Mac 上访问 iPhone 走系统 `usbmuxd`，不用换驱动
3. **我们的 gadget（VID 0x1209）不会被系统驱动抢**：macOS 只对已知 VID 自动加载驱动
4. **MacPlay 在 Mac 上是亲生的**：可以直接 `cargo run` 它的 `usb-probe` / helperd

### 4.2 安装 Rust（约 1 分钟）

```bash
curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh
source "$HOME/.cargo/env"
rustc --version
```

### 4.3 编译 carplay-probe

```bash
cd tools/wired-usb/carplay-probe
cargo build --release
# 产物：target/release/carplay-probe
```

它只依赖 `nusb`（纯 Rust 跨平台：macOS 走 IOKit）。

### 4.4 adb

```bash
# 确认 adb 路径（Android Studio 默认）
export ANDROID_HOME="$HOME/Library/Android/sdk"
export PATH="$PATH:$ANDROID_HOME/platform-tools"
adb devices
```

---

## 5. 手机端：抢 UDC 建 gadget

### 5.1 前置：开无线 adb（强烈建议）

绑定 UDC 后 USB adb **立刻断**。虽然在 Mac 上拔插可恢复，但有了无线 adb 就不用拔插。

```bash
# 写入持久属性（本次不生效，下次开机自动开 5555；零风险，不重启 adbd）
adb shell su -c "setprop persist.adb.tcp.port 5555"

# 本次想立刻生效（注意：Windows 上疑似由 stop adbd 触发过一次重启，谨慎）
adb shell su -c "stop adbd; start adbd"
adb connect 192.168.2.147:5555
```

或者用开发者选项里的「无线调试」（Android 11+ 配对码，与 adb tcpip 是两套，更稳）。

### 5.2 推脚本并运行

```bash
cd tools/wired-usb
adb push phone/gadget-carplay.sh  /sdcard/
adb push phone/gadget-restore.sh  /sdcard/
adb shell su -c "chmod 755 /sdcard/gadget-*.sh"

# 起 gadget（脚本自带 90 秒自动还原安全网）
adb shell su -c "AUTO_RESTORE_SEC=90 sh /sdcard/gadget-carplay.sh"
```

脚本设计要点：

- **每步落盘**到 `/sdcard/gadget.log` —— 因为解绑 g1 后 stdout 就看不到了，日志是唯一取证手段
- **先 `setprop sys.usb.config none`** 停 USB HAL，否则 HAL 检测到 UDC 空闲会立刻抢回 g1
  （实测过：不停止的话手机会变回 `2717:ff48`）
- NCM 实例名自动回退尝试：`ncm.usb0` → `ncm.0` → `ncm.gs0`
- 定时自动还原（`setsid` 脱离 adb shell 进程组，否则会被一起杀）

### 5.3 还原（出问题随时执行）

```bash
adb shell su -c "sh /sdcard/gadget-restore.sh"
# 若 USB adb 已断：
adb -s 192.168.2.147:5555 shell su -c "sh /sdcard/gadget-restore.sh"
```

---

## 6. Mac 端：探测 gadget

```bash
cd tools/wired-usb/carplay-probe
./target/release/carplay-probe 1209          # 看 gadget 的配置/接口/端点
./target/release/carplay-probe               # 无参数 = 列全部 USB 设备
./target/release/carplay-probe 05ac          # 看真 iPhone（若插着）
```

### 三种结果与含义

| 现象 | 含义 |
|---|---|
| 枚举成功 | 🎉 root 抢 UDC 可行，继续加 `ffs.iap2` |
| 设备出现但打不开 | 只是驱动/权限问题，**路线仍然成立** |
| 根本不出现 | 小米 USB HAL 把 UDC 抢回去了，转 §8 |

### 关于 VID 的选择

脚本用 **非 Apple VID `0x1209`**（pid.codes 测试用）。
原因：见到 `0x05ac` 时系统会自动装 Apple 驱动并独占设备，拿不到句柄。
**等枚举验证通过、真要接车机时再换成 `0x05ac`。**

---

## 7. 通过后：加 iAP2 通道

1. **加 `ffs.iap2`**（FunctionFS）：需要一个用户态 daemon（JNI 或独立 native 进程）
   处理 `ep0` 的描述符/事件 + bulk 端点读写。
   内核侧 `USB_F_FS=y` 已确认在位，`/dev/usb-ffs/` 存在且在被系统使用。
2. **伪装成 iPhone 枚举**：VID `0x05ac` + iAP2 接口 `ff/fe/02`
   + CarPlay 配置的 NCM 网口 `ff/fd/01`（alt 1 起才有 bulk）
3. **复用现有 iAP2 链路**：`transport/DuplexStream.kt` 的
   `StreamDuplexStream(in, out, onClose)` 已是通用流抽象，
   **全工程只有蓝牙两处使用** → 加一个 USB 实现即可，不用重构。
4. iAP2 链路参数（从 MacPlay 抄）：`max_outgoing=4, control_version=2, zero_ack=true`

### MacPlay 里能白拿的零件

| 零件 | 位置 | 用途 |
|---|---|---|
| `usb-probe` | `native/livi-helperd/bin/usb-probe` | 现成探针（注释写着 "Cross-platform nusb groundwork"） |
| vendor request | **0x52 / wIndex=4** | 让 iPhone 重新枚举出 CarPlay 配置（`CP_CONFIG=6`, `DEFAULT_CONFIG=4`） |
| NCM 发现方式 | `iap2-usbmux/src/ncm.rs` | alt setting 1 才是带 bulk 端点的那个 |
| iAP2 链路参数 | `iap2-link` | max_outgoing / control_version / zero_ack |

⚠️ `assets/authentication/` 里的 MFi 材料源自 DiPlay 0.2.6，**再分发授权未确认，别公开发**。

---

## 8. 备选：外接硬件桥（若 UDC 抢不下来）

手机 →(Wi-Fi/TCP)→ **树莓派 Zero 2 W**（dwc2 gadget: vendor bulk + NCM）→(USB)→ 车机

优点：

- **手机端零内核改动、完全不依赖 root** → 今天碰到的重启、root 失效、HAL 抢 UDC 全都不会有
- 复用 `StreamDuplexStream` 抽象，加 `TcpDuplexStream` + `Iap2TcpClient` 即可

代价：一块板子 + 一根线 + 供电。

**如果 §5 拿到 EBUSY 且绕不过，就走这条，别在内核层继续耗。**

---

## 9. 坑与注意事项（血泪）

### adb / shell

- **`adb shell su -c "..."` 里带换行的多行命令会掉出 root** —— 只有第一行以 root 执行，
  后续行落回 `uid=2000(shell)`。表现为「setenforce 已 Permissive 但 configfs 仍 Permission denied」。
  **必须写成 `;` 分隔的单行，或每条命令单独调用。**
- **Git Bash（Windows）下 `adb push xxx /sdcard/` 会被当本地路径**
  （报 `remote secure_mkdirs() failed`），要用 `//sdcard/` 前缀。
- 读 configfs 要用 `cat`（能读）；`ls -ld` 单文件会 Permission denied（stat 受限），别据此误判没权限。
- **KernelSU 是 PoC 内存注入式，内核状态脆弱**：
  Windows 上执行 `adb tcpip 5555` 后手机强制重启过一次
  （bootreason 为 `reboot`、无 system_server/watchdog 崩溃记录 → 指向内核层）。
  **这台机器经不起内核层折腾，动内核前先想好退路。**

### nusb（跨平台 USB 库）

- **`Device::control_in` 在 Windows 不支持**（源码 cfg gate 直接排除），
  必须 `claim_interface()` 后用 `Interface::control_in`。
  MacPlay 的 `request_carplay_configs()` 用的是 Device 版，**Windows 上编译不过**。
  macOS 上无此限制。
- `set_configuration()`、`Interface::control_in` 在 Windows 可用。
- WinUSB 限制：recipient 为 Interface 时，index 低字节必须等于接口号。

### 其他

- **UDC 是独占的**，不能「另起一个 gadget 并存」，必须抢。
- 脚本会把 `persist.sys.usb.config` 改成 `none`，**动完必须还原**（见 §3）。
- 强杀 App 会断 CarPlay 会话，非必要不要 `am force-stop`。

---

## 10. 文件清单

```
tools/wired-usb/
  phone/gadget-carplay.sh       # 手机端起 gadget（每步落盘 + 停 HAL + 自动还原）
  phone/gadget-restore.sh       # 手机端还原（UDC 还给 g1）
  pc/verify-gadget.sh           # 一键验证（无线 adb 硬门槛，不通就拒绝起 gadget）
  pc/probe.py                   # pyusb 版探针（列设备 / dump 描述符 / bulk 试探）
  pc/usbmux-probe.py            # usbmux 通道探针（Windows 上被拒，Mac 上可复用）
  carplay-probe/                # Rust 探针（nusb，跨平台），端点级 dump
```

依赖：`pip install pyusb libusb-package`（probe.py 用）；
`carplay-probe` 只需 `cargo build --release`。
