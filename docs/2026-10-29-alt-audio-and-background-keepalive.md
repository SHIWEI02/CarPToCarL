# 导航独立音频流（alt audio）+ 后台保活 —— 2026-10-29 更新说明

分支 `fix/tts-on-112`，基于上游 v1.1.2。

---

## 一、这次更新了什么

按主题归类，不逐条列提交。

### 1. 导航播报走**独立音频流**（核心）

以前导航 TTS 和音乐混在同一条 `type=102` 流里：每播一次报，流格式就要
`48k 立体声 → 16k 单声道 → 48k 立体声` 来回切，边界必然 glitch，播报期间
音乐还得让路写静音。

现在按 CarPlay 原生设计拆成两条流：

| 用途 | type | audioType | codec | 缓冲路径 |
|---|---|---|---|---|
| 音乐 | 102 (MainHighAudio) | `media` | AAC-LC 48k 2ch | `realtime=false`，250ms |
| 导航 | 101 (AltAudio) | `alert` | OPUS 48k 1ch | `realtime=true`，短路径 |

MacPlay 实测确认两条独立管道：

```
[cp_audio] stream 0x7c000001 open (AacLc)
[cp_audio] stream 0x7c000002 open (Opus)
UI: AAC-LC · 48000Hz · 2ch / OPUS · 48000Hz · 1ch
```

### 2. 修掉四个真 bug

**① OPUS 编码器静默丢帧**（卡顿的真因）

```kotlin
while (offset < pcmFrame.size) {
    val inIndex = c.dequeueInputBuffer(TIMEOUT_US)  // 只等 10ms
    if (inIndex < 0) break                          // 拿不到就跳出
}
// offset < size 时无声退出，剩余 PCM 直接丢，无任何补偿
```

MediaCodec 输入 buffer 不一定随时可得，超时就把这一帧剩下的数据丢掉。
一帧 = 20ms，丢一帧就是 20ms 空档。改为队列模型后实测
`喂入 599 帧 / 产出 599 包 / 队列余 0`，零丢帧。

**② 16k→48k 上采样的成像失真**（沙沙的来源）

线性插值核是三角窗（频响 sinc²），8kHz 处只衰减 3.5dB，8–24kHz 全是镜像。
24kbps 时 Opus 把这些高频当噪声丢掉（听不到但发闷），提到 96kbps 后忠实
保留 → 杂音暴露。加两级 Butterworth 低通（4 阶，fc = 0.45×源采样率）后
镜像降到相对语音核心 −54.6dB。

**③ 通话看门狗误判导航为来电**

CarLife 播 TTS 用「语音通信」用途的 AudioTrack，会把 `AudioManager.mode`
抬到 `IN_COMMUNICATION` 再放下。看门狗把每次播报当成「通话结束」，拆掉主
媒体流重建，实测媒体空窗 16 秒。修法：写 TTS 包时回填时间戳，看门狗在 mode
回落时先查 3 秒内有无 TTS 包，有则只记日志不重建。

**④ 切后台投屏 30 秒必断**

不是网络、不是 Doze、不是进程被杀。MIUI 的 SmartPower 切后台 3 秒后把进程
标成 idle 并整体挂起（连 `Log.i` 都不输出），数据无人接收，接收端判死拆链：

```
21:03:09.642 I/SmartPower: io.github.shaonialife.carptocar/10401(31955):
             background->idle(3000ms) R(become invisible) adj=200
```

顺带把调试日志改成**异步批量写**（原来同步 open→write→close 还持全局锁，
热路径每秒几十次，存储一慢就拖住业务线程）。

### 3. 参数对齐真机

| 项 | 原值 | 现值 | 依据 |
|---|---|---|---|
| 导航码率 | 24 kbps | **96 kbps** | Apple 48kHz 档位（≤24k→48k，≤32k→64k，48k→96k） |
| 媒体延迟 | 250 ms | **500 ms** | 业界适配器默认 1000；转义链路多一层，取折中 |
| 导航延迟 | 200 ms | **400 ms** | 同上，保持比媒体紧 |

实测效果：`jitterLost` 从 22/9 降到 0。

### 4. 诊断埋点（可开关，不用重编）

```
AUDIO-DIAG[101]: 喂入 150 包 / 已发 599 包 · 源间隔 平均 0ms 最大 0ms
                 · 编码器 喂入 599 帧 / 产出 599 包 / 队列余 0 帧
AUDIO-FILTER[101]: 抗成像低通 fc=7200Hz（16000Hz → 48000Hz）第 1 次重置
SVC: onStartCommand startId=1 重投递=false 重试=false
POWER: 不在电池优化白名单——请求加入
```

### 5. 后台保活加固

Manifest 加 `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`，连接时检查白名单并发
`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`；服务加
`stopWithTask="false"` + `onTaskRemoved`；`onStartCommand` 记录重投递标志
（出现 `重投递=true` 就是被系统杀过又重启的铁证）。

已有但未改的：`PARTIAL_WAKE_LOCK`（无超时）+ `WIFI_MODE_FULL_LOW_LATENCY` +
`START_STICKY` + ongoing 通知 + 显式 `connectedDevice` 前台类型。

---

## 二、技术思路

一句话：**CarLife（车机协议）→ 转义 → CarPlay（手机协议），音频按 CarPlay
原生拓扑对齐，而不是把两路混在一条流里凑合。**

三条关键认知都来自 MacPlay 的 `engine/protocol/cpStack.js`（作者实测过真机）：

**① 流类型常量**

```
MAIN_AUDIO=100  ALT_AUDIO=101  MAIN_HIGH_AUDIO=102
MAIN_SCREEN=110 ALT_SCREEN=111 DATA=130
```

早期猜的 108 根本不存在，接收端直接回 `stream type 108 not handled yet`。

**② `audioType` 决定播放路径 —— 这是「播报开头卡」的协议层根因**

```js
realtime: prof.audioType !== 3
// media → audioType=3 → realtime=false → 250ms 缓冲（给音乐）
// alert → default 分支 → audioType=4 → realtime=true → 短路径 + AudioNaviStart
```

导航挂在 `media` 上，等于被塞进为音乐调优的大缓冲。

**③ 无线 CarPlay 的编码选择**

> PCM is USB-only, so over wireless the phone picks OPUS for the low-latency
> streams (100/101) and AAC-LC for the entertainment stream (102).

---

## 三、下一步计划

按优先级排。

### P0 真车验证（奥迪 MMI）

要看车机**实际声明**了什么，而不是照搬 MacPlay 的假设：

- `/info` 的 `audioFormats` 逐条（决定能用哪种编码/audioType）
- `type=101` 是否被接受；被拒会自动回落「单流 + 静音压制」，日志会写明
- 双流是否真的建起来

若车机不支持 101，按它声明的能力调 `alt_audio_stream_type`（settings 可改，
不用重编）。

### P1 16k 直通（如果杂音仍在）

现在链路是：`16k 源 → 线性插值上采样 48k → Opus 48k fullband 编码`。
凭空造出 8–24kHz 内容，再用滤波器压回去。

根本解是**不做上采样**：让 Opus 用 16k wideband 模式（语音专用），解码端
（Opus 内部是高质量重采样器）再升到 48k。

⚠️ 关键陷阱：RFC 7587 §4.1 规定 **RTP 时钟固定 48kHz**，与编码采样率无关。
16k 时每帧 320 样本，时间戳增量仍必须是 `320 × 48000/16000 = 960`。
算成 320 会导致播放速度慢 3 倍。

### P2 一次性杂音（可选）

101 流首次建立时 Mac 音频输出设备会重启一次（`deviceRestarts=1`），
重启瞬间有一声杂音，之后同会话不再出现。可选做法：流建立后先发 200ms
静音预热，代价是播报延迟 +200ms。**倾向不做**。

### P3 后台保活验证

设 MIUI 省电策略为「无限制」后复测，判断标准：

```
dumpsys power | grep SmartPower   → 不应再出现 carptocar background->idle
```

### P4 遗留的功能缺口

- `telephony` / `speechRecognition` 尚未独立成流（真机各有一条低延迟流）
- `iAPSendMessage` 隧道（`type=130`）未建，触摸 HID 走不通

---

## 四、安卓侧需要提取什么、怎么分析

### 4.1 一键采集脚本

```bash
PKG=io.github.shaonialife.carptocar
OUT=~/Desktop/carplay-dump-$(date +%H%M)

mkdir -p "$OUT"

# 1) app 日志（最重要，内置 16MB 上限，异步写不阻塞业务）
adb shell su -c "cat /data/data/$PKG/files/main_debug.log" > "$OUT/main_debug.log"

# 2) 会话报文录制（按会话切分，失败会带 fail 前缀）
adb shell su -c "ls /data/data/$PKG/files/session_trace_*.log 2>/dev/null" \
  | tr -d '\r' | while read f; do
      [ -n "$f" ] && adb shell su -c "cat $f" > "$OUT/$(basename $f)"
    done

# 3) 进程与调度状态
adb shell "dumpsys activity processes" > "$OUT/processes.txt"
adb shell "dumpsys activity services $PKG" > "$OUT/services.txt"
adb shell "dumpsys power" > "$OUT/power.txt"
adb shell "dumpsys deviceidle whitelist" > "$OUT/doze_whitelist.txt"
adb shell "dumpsys notification --noredact" > "$OUT/notification.txt"

# 4) 网络（CarLife 端口 + AirPlay）
adb shell su -c "cat /proc/net/tcp" > "$OUT/tcp.txt"
adb shell su -c "cat /proc/net/unix" > "$OUT/unix.txt"

# 5) 蓝牙 / WiFi
adb shell "dumpsys bluetooth_manager" > "$OUT/bluetooth.txt"
adb shell "dumpsys wifi" > "$OUT/wifi.txt"

# 6) 进程快照
adb shell "ps -A -o PID,UID,STAT,NAME" > "$OUT/ps.txt"
```

### 4.2 各类数据的判读方法

#### ① `main_debug.log` —— 主证据

**音频链路是否健康**

```
AIRPLAY: 音频能力[N] ...            → 车机声明了哪些编码/audioType
AIRPLAY: ✓ 音频流协商成功（type=...） → 媒体流走哪条
ALT-AUDIO: ✓ / ✗ type=101            → 导航独立流成没成
```

**有没有丢帧**（最关键）

```
AUDIO-DIAG[101]: 喂入 N 包 / 已发 M 包 · 编码器 喂入 A 帧 / 产出 B 包 / 队列余 C 帧
```

| 现象 | 判断 |
|---|---|
| `A ≈ B` 且 `C = 0` | 编码器正常（B 比 A 少 1 是 Opus lookahead，正常） |
| `C` 持续增长 | 编码器积压，下游发不出去 |
| `A != B` 且 `C=0` | 编码器丢帧 |

**源数据节奏**

```
· 源间隔 平均 40ms     → 实时流（媒体正常）
· 源间隔 平均 0ms      → CarLife 整段灌入（TTS 就是这样，不是异常）
· 最大间隔突然变大      → 源本身有卡顿，不是我们造成的
```

**滤波器状态**

```
AUDIO-FILTER[101]: ... 第 K 次重置
```

`K > 1` 说明源采样率在变，每次重置都会产生一次瞬态（可闻咔哒）。

**后台是否被降级**

```
SmartPower: ...carptocar... background->idle
SVC: onStartCommand ... 重投递=true
```

出现 `background->idle` = MIUI 省电策略在作用；`重投递=true` = 服务被杀过。

**断链是谁先断的**

| 日志 | 含义 |
|---|---|
| `SESSION 保活结束（链路断开）` | 对端先断（iAP2 链路 read 返回 EOF） |
| `VIDEO: 直通 keepAlive 中止 - Broken pipe` | 视频 TCP 被对端关闭 |
| `AUDIO: 同步包发送失败 - ECONNREFUSED` | 接收端端口已关（拆链之后） |
| `SVC: MediaProjection 被系统停止` | 投影授权被系统收回 |

#### ② `processes.txt` —— 判断进程是否被限流

```bash
grep -A30 "ProcessRecord.*carptocar" processes.txt | grep -E "adj=|curProcState|frozen"
```

| 字段 | 正常值 | 异常含义 |
|---|---|---|
| `adj` | 200（PERCEPTIBLE_APP，前台服务正常值） | 700+ = cached，会被冻结 |
| `curProcState` | 4（FOREGROUND_SERVICE） | 5+ = 掉出前台服务 |
| `frozen` | `false` | `true` = 被 CPU 冻结 |

#### ③ `tcp.txt` —— 判断连接是否还在

`/proc/net/tcp` 是十六进制，端口要转：

```bash
python3 - <<'PY'
import sys
for ln in open('/tmp/tcp.txt'):
    f = ln.split()
    if len(f) < 4: continue
    try:
        lp = int(f[1].split(':')[1], 16); rp = int(f[2].split(':')[1], 16)
    except: continue
    if lp in (7240, 9240, 9241) or rp in (7240, 9240, 9241):
        st = {'01':'ESTAB','0A':'LISTEN','06':'TIME_WAIT','08':'CLOSE_WAIT'}.get(f[3], f[3])
        print(f'{st:12s} local={lp} remote={rp}')
PY
```

| 端口 | 用途 |
|---|---|
| 7240 | CarLife 引擎守候（app 劫持入口） |
| 9240 | CarLife 媒体音频（→ 102 流） |
| 9241 | CarLife TTS 音频（→ 101 流） |

**端口消失 = CarLife 会话已断**，这时看 app 有没有对应日志，就能区分是
「CarLife 主动断」还是「我们这侧读线程停了」。

#### ④ `ps.txt` + `/proc/PID/stat` —— 区分「冻结」和「卡在 I/O」

```bash
A=$(adb shell "cat /proc/$PID/stat" | awk '{print $14+$15}'); sleep 3
B=$(adb shell "cat /proc/$PID/stat" | awk '{print $14+$15}')
echo "CPU ticks: $A → $B"
```

| 观察 | 结论 |
|---|---|
| CPU 时间在涨 + 有日志 | 正常运行 |
| CPU 时间在涨 + **无日志** | 线程卡在锁/IO，进程没死 |
| CPU 时间不涨 + 无日志 | 进程被冻结 |

今天就是靠这个区分出来的：CPU 在涨但零日志 → 不是冻结。

#### ⑤ `power.txt` —— MIUI 省电策略

```bash
grep -i "carptocar\|SmartPower\|PowerKeeper" power.txt | tail -20
```

关注 `background->idle`、`become invisible`、`adj=`。

#### ⑥ `doze_whitelist.txt`

```bash
grep -i carpto doze_whitelist.txt || echo "不在白名单"
```

不在白名单时，Doze 会挂起后台网络（WakeLock 只保 CPU 不保网络）。

#### ⑦ `bluetooth.txt` —— 连不上车机时

```bash
grep -iE "ACL|Connected count|Bonded|Pairing Algorithm" bluetooth.txt
```

配合 app 侧的 `BT-C: read failed` / `SDP 明确不含 iAP2 服务` 一起看。

---

## 五、常见误判（踩过的坑）

| 现象 | 错误归因 | 真因 |
|---|---|---|
| 会话日志里缺一段 | logcat 缓冲被冲掉 | 进程被 SmartPower 挂起，连 `Log.i` 都没执行 |
| 投屏 30 秒断 | Doze 限网 | MIUI SmartPower 降级（`background->idle`） |
| `iAP2 链路协商超时` | 真的超时 | `awaitReady` 的 `return !terminated` 把「链路被终止」误报成超时 |
| `adj=200` | 进程被降级 | 这是前台服务的**正常** adj 值 |
| 6 阶滤波更好 | 抑制更多 | 数值干净但体感更差，4 阶足够（−54.6dB 已在掩蔽阈值下） |
| MacPlay `device class result: 0` | 设备异常 | 所有历史日志都是 0，是常态 |

---

## 附：运行开关（改 settings 即生效，不用重编）

```
alt_audio_stream_enabled   bool    导航独立流总开关（true）
alt_audio_stream_type      int     101（历史值 108 会被迁移逻辑忽略）
alt_audio_codec            string  "opus"（默认）/ "pcm"
alt_opus_bitrate           int     96000（Apple 48kHz 档位）
audio_pacing_enabled       bool    发送均匀化（true）
debug_dump_audio           bool    音频落盘（默认关，分析时临时开）
bt_active_connect_enabled  bool    默认 true；只监听场景设 false
```

路径：`/data/data/io.github.shaonialife.carptocar/shared_prefs/settings.xml`

⚠️ `debug_dump_audio` 打开后会往 `files/` 写 `tts-src-*.bin / tts-dst-*.bin`
（最多 80 包），可用 numpy 做频谱对比，分析完记得关掉。
