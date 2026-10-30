package io.github.shaonialife.carptocar.carlife

/**
 * CarLife 车机端（HU）协议常量与小工具。
 *
 * 协议细节来自 2026-10-23 实机验证（见 docs/carlife-hijack-findings.md）：
 * 手机端（MD）监听 7 个 TCP 端口，车机端主动连接；控制通道 8 字节头，
 * 其余通道 12 字节头；protobuf 手写编码，不引入 protobuf 运行时。
 */
internal object CarLifeProtocol {

    // ---- 端口 ----
    const val PORT_CMD = 7240
    const val PORT_VIDEO = 8240
    const val PORT_MEDIA = 9240
    const val PORT_TTS = 9241
    const val PORT_VR = 9242
    const val PORT_TOUCH = 9340
    const val PORT_DATA = 9440

    // ---- 消息 ID ----
    const val HU_PROTOCOL_VERSION = 98305
    const val MD_VERSION_MATCH = 65538
    const val HU_STATISTICS = 98343
    const val MD_INFO = 65540
    const val HU_INFO = 98307
    const val HU_AUTHEN_ACK = 98380
    const val HU_VIDEO_ENC_INIT = 98311
    const val VIDEO_ENC_START = 98313

    /**
     * 帧率变更请求 / 确认（`VIDEO_ENCODER_FRAME_RATE_CHANGE`）。
     *
     * 命令号来自手机包 `com.baidu.carlife.core.CommonParams` 的常量表：
     *  - MSG_CMD_VIDEO_ENCODER_FRAME_RATE_CHANGE      = 98316
     *  - MSG_CMD_VIDEO_ENCODER_FRAME_RATE_CHANGE_DONE = 65549
     *  - MSG_CMD_VIDEO_ENCODER_PAUSE = 98314 / RESET = 98315 / START = 98313
     *
     * 载荷是 `CarlifeVideoFrameRateProto.CarlifeVideoFrameRate`（field1 = frameRate）。
     * 98311 里自带的 fps 字段引擎**只回显不生效**，真正的帧率通道是这条。
     */
    const val HU_VIDEO_FRAME_RATE = 98316
    const val MD_VIDEO_FRAME_RATE_DONE = 65549

    /** 视频编码器暂停 / 复位（保留常量，便于排查，不要用来改帧率）。 */
    const val HU_VIDEO_ENC_PAUSE = 98314
    const val HU_VIDEO_ENC_RESET = 98315

    /**
     * 帧率变更命令候选 id。
     *
     * 字节码铁证（2026-10-24 第四轮逆向）：`Recorder.changeFrameRate` 的范围判定是
     * `clamp(frame, 3, 60)`（const/16 #3 / const/16 #60），**不是旧结论的 3~30**。
     * 所以这里把上限修正为 60——98316 直接发 60 是合法且会被引擎采纳的。
     */
    val FRAME_RATE_PROBE_IDS = intArrayOf(HU_VIDEO_FRAME_RATE)

    /** 手机端引擎的硬上限（字节码实证 `Recorder.changeFrameRate` 判 3..60）。 */
    const val FRAME_RATE_MAX = 60
    const val MD_VIDEO_ENC_INIT = 65544
    const val VIDEO_ENC_DONE = 65551
    const val HU_FEATURE_CONFIG = 98386
    const val MD_FEATURE_CONFIG = 65617
    const val MD_MODULE_STATUS = 65574
    const val HU_MODULE_CONTROL = 98344

    /**
     * 98386 的确认（0 字节，引擎收到特征配置后回）。
     *
     * 实测时序：发 98386 → 约 0.7s 后收到 65570 → 再发 98311。所以它可以当
     * 「特征配置已被接受」的凭据，比盲发有用。
     */
    const val MD_FEATURE_CONFIG_DONE = 65570

    /**
     * 手机端主动推送的媒体信息（歌名/歌手/专辑/封面/时长）。
     *
     * 命令号来自 `com.baidu.carlife.core.CommonParams.MSG_CMD_MEDIA_INFO = 0x00010035`，
     * 载荷是 `CarlifeMediaInfoListProto.CarlifeMediaInfoList`（见车机端参考实现
     * CTranRecvPackageProcess.cpp 的 0x10035 分支）。方向 MD→HU：手机 CarLife 内置
     * 播放器开始播放音乐时，由 `sendMusicInfoToVehicle` 主动推给车机。
     */
    const val MD_MEDIA_INFO = 65589

    /** 手机端媒体进度条（`MSG_CMD_MEDIA_PROGRESS_BAR = 0x00010036`，载荷 u32 progressBar）。 */
    const val MD_MEDIA_PROGRESS = 65590

    /**
     * 手机自发的状态广播，都是 0 字节、无需回应。
     *
     * 65563(0x1001B)/65560(0x10018) 在版本匹配前后就会到，属于引擎的常规状态上报。
     * 留着不打日志，避免每次握手都刷三行「未处理」，把真正有用的行挤掉。
     */
    val MD_IGNORED_IDS = intArrayOf(65560, 65563)

    // ---- 触控（9340 / Touch 通道，**控制帧格式** 8B 头）----
    //
    // 消息 ID 以 **HU 端官方 SDK** 为准（.tools/carlife-ref 的
    // CarLife-Android-Vehicle/src/com/baidu/carlifevehicle/CommonParams.java）：
    //
    //   0x68001 MSG_TOUCH_ACTION        复合动作（CarlifeTouchAction{action,x,y,…}）
    //   0x68002 MSG_TOUCH_ACTION_DOWN   单点（CarlifeTouchSinglePoint{x,y}）
    //   0x68003 MSG_TOUCH_ACTION_UP     单点
    //   0x68004 MSG_TOUCH_ACTION_MOVE   单点
    //   0x68005 SINGLE_CLICK · 0x68006 DOUBLE_CLICK · 0x68007 LONG_PRESS
    //   0x68008 CAR_HARD_KEY_CODE · 0x6800A ACTION_BEGIN · 0x6800D 复合（MD 端）
    //
    // **踩过的坑（2026-10-25）**：这里曾按「0x68001 DOWN / 0x68002 MOVE / 0x68003 UP」
    // 连号推断，结果整条手势全错位——`0x68002` 其实是 UP、`0x68003` 才是 UP 的邻居
    // 0x68003 同样是 UP（MOVE 在 0x68004）。于是车机端每次拖动变成：
    //   0x68001（复合动作，action 缺省=按下）→ 0x68002（=抬起！）→ …
    // 手指刚动第一下引擎就收到「抬起」，手势立刻结束，只剩原地一次点击。
    // 表现正是「能点、点得准，但拖不动、列表不跟手」。坐标映射一直是对的，别再去调缩放。
    const val TOUCH_ACTION = 425985 // 0x68001 复合动作：负载带原始动作码，双指手势走这里
    const val TOUCH_DOWN = 425986 // 0x68002
    const val TOUCH_UP = 425987 // 0x68003
    const val TOUCH_MOVE = 425988 // 0x68004

    /**
     * [TOUCH_ACTION] 负载里的动作码——就是 Android `MotionEvent` 的动作码，
     * 指针索引放在高字节（`action or (index shl 8)`），与官方 HU SDK
     * 「新触摸方式」`sendAction(event.getX(), event.getY(), event.getAction())` 一致。
     *
     * 为什么双指必须走这条路：单点消息（0x68002/3/4）的负载是
     * `CarlifeTouchSinglePoint{x,y}`，**没有指针概念**，第二根手指无从表达——
     * 旧实现直接把 slot 1 丢掉，双指缩放自然按不出来。
     */
    object TouchAction {
        const val DOWN = 0
        const val UP = 1
        const val MOVE = 2
        const val POINTER_DOWN = 5
        const val POINTER_UP = 6
        const val POINTER_INDEX_SHIFT = 8

        /** 给动作码带上指针索引（槽 0 → 手指 0，槽 1 → 手指 1）。 */
        fun withPointer(
            base: Int,
            pointerIndex: Int,
        ): Int = base or (pointerIndex shl POINTER_INDEX_SHIFT)
    }

    const val HEARTBEAT = 131074

    /** CONNECT_TYPE：5 = Wi-Fi 局域网 */
    const val CONNECT_TYPE_WIFI = 5

    // ---- 音频（9240 媒体 / 9241 TTS）----
    //
    // 音频通道是**裸字节流**，不走控制通道的 8B 头，也不走视频的 [len][seq][type]：
    // 每包 = 12B 包头 + 负载（手机端 PCMPackageHead 逐字节写死）：
    //   [0..3] 负载长度 BE | [4..7] 时间戳 ms BE | [8..11] 包类型 BE
    // 包类型来自 AudioUtil.EMusicPackageType：INIT=0x30001（负载是 CarlifeMusicInit
    // 原型，宣告采样率/声道/位深），NORMAL=0x30006（负载是裸 PCM）。
    //
    // 0x30002 / 0x30003 是**长度 0 的开停信号**（2026-10-26 从真机日志反推，见 docs/audio-call-issues.md）：
    //   0x30002 出现后立刻来首帧 PCM（10:03:16、10:24:19 两次）；
    //   0x30003 出现后引擎音频归零（10:21:13、10:31:50 两次，均为来电期间）。
    // 语义按「开始 / 停止」处理，再往上叠一层兜底（停止后仍收 PCM 照常转发，不靠它裁决）。
    const val AUDIO_HEAD_LEN = 12
    const val MUSIC_PKG_INIT = 0x30001
    const val MUSIC_PKG_START = 0x30002
    const val MUSIC_PKG_STOP = 0x30003
    const val MUSIC_PKG_NORMAL = 0x30006

    // ---- TTS（9241 端口）：导航播报 / 语音提示 ----
    //
    // 关键：**这条通道才是导航声音的来源**，与媒体通道（9240 / 0x3xxxx）完全独立。
    // 2026-10-27 真机日志（WinPlay 测试台）实测：
    //   0x40001 长度 7    → 格式宣告（CarlifeMusicInit 同构）
    //   0x40002 长度 0    → 开始
    //   0x40003 长度 1280 → **裸 PCM（导航播报数据）**
    // 编号与媒体通道**不对应**（媒体数据包是 0x30006，TTS 却在 0x40003 上带负载），
    // 所以这里不照搬编号做枚举，只认两条铁律：**有负载就是 PCM，长度 0 就是开停信号**。
    // 旧实现把 0x4xxxx 全丢进「暂不处理」，症状正是「音乐有声、地图导航没声」。
    const val TTS_PKG_INIT = 0x40001

    /** TTS 未宣告格式时的兜底采样率（导航播报常见 16k 单声道，AudioSender 会重采样到 48k）。 */
    const val TTS_DEFAULT_RATE = 16_000

    // ---- 帧格式 ----

    /** 控制通道（Channel 1）：[u16 len][u16 reserved][u32 service_type] */
    fun packCommand(serviceType: Int, payload: ByteArray): ByteArray {
        val out = ByteArray(8 + payload.size)
        writeU16(out, 0, payload.size)
        writeU16(out, 2, 0)
        writeU32(out, 4, serviceType)
        System.arraycopy(payload, 0, out, 8, payload.size)
        return out
    }

    /** 数据通道（非 Channel 1）：[u32 len][u32 seq][u32 service_type] */
    fun packStream(seq: Int, serviceType: Int, payload: ByteArray): ByteArray {
        val out = ByteArray(12 + payload.size)
        writeU32(out, 0, payload.size)
        writeU32(out, 4, seq)
        writeU32(out, 8, serviceType)
        System.arraycopy(payload, 0, out, 12, payload.size)
        return out
    }

    // ---- protobuf 手写编码 ----

    fun pbVarint(field: Int, value: Int): ByteArray {
        val head = byteArrayOf(((field shl 3) or 0).toByte())
        return head + varintBytes(value.toLong())
    }

    fun pbString(field: Int, value: String): ByteArray = pbBytes(field, value.toByteArray(Charsets.UTF_8))

    fun pbBytes(field: Int, value: ByteArray): ByteArray {
        val head = byteArrayOf(((field shl 3) or 2).toByte())
        return head + varintBytes(value.size.toLong()) + value
    }

    private fun varintBytes(input: Long): ByteArray {
        var value = input
        val out = ArrayList<Byte>(8)
        while (true) {
            val low = (value and 0x7F).toInt()
            value = value ushr 7
            if (value == 0L) {
                out.add(low.toByte())
                return out.toByteArray()
            }
            out.add((low or 0x80).toByte())
        }
    }

    // ---- 载荷构造 ----

    /**
     * 协议版本 **3.0**，不是 4.0。
     *
     * MD 端 `updateVehicleVersion()`：`major==4` 或 `(3,2)` 会置「新车机版本」，
     * 从而走 RSA/AES 加密鉴权且永不下发 65611，会话静默挂死；而手机支持列表是
     * {1.0, 2.0, 3.0, 4.0}，发 3.0 既 match=1 又走老版明文路径。
     */
    fun protocolVersion(): ByteArray = pbVarint(1, 3) + pbVarint(2, 0)

    fun statisticsInfo(): ByteArray =
        pbString(1, "12345678") + // cuid
            pbString(2, "4.0.0") + // versionName
            pbVarint(3, 16) + // versionCode
            pbString(4, "20029999") // channel

    /**
     * 车机设备信息。字段号必须与官方车机版 `CarlifeDeviceInfoProto` 一致：
     * 1 os, 2 board, 3 bootloader, 4 brand, 5 cpuAbi, 6 cpuAbi2, 7 device, 8 display…
     * 早期按猜测把字符串写进 sdkInt 等整型位，对端 protobuf 解析抛异常后静默断链。
     */
    fun deviceInfo(
        display: String = "1280x720",
    ): ByteArray =
        pbString(1, "Android") +
            pbString(2, "msm8998") +
            pbString(3, "unknown") +
            pbString(4, "baidu") +
            pbString(5, "arm64-v8a") +
            pbString(6, "arm64-v8a") +
            pbString(7, "CarLifeVehicle") +
            pbString(8, display)

    /**
     * 每多少个 P 帧插一个 I 帧。
     *
     * 原值 300 = 20fps 下 **15 秒才一个 I 帧**：车机端一旦丢包/花屏要熬 15 秒才自愈，
     * 且 I 帧稀少意味着长时间没有完整画面重建。改成 20（=1 秒）后：
     *  - 车机端花屏 1 秒内自愈；
     *  - 每秒都有完整帧，平均码率随之上升（I 帧远大于 P 帧），1080p 下的块效应明显减轻。
     */
    const val I_FRAME_INTERVAL_DEFAULT = 20

    /**
     * 特征配置：CONNECT_TYPE + I 帧间隔 + 分辨率直通 + 歌词支持。
     *
     * 2026-10-25 逆向 `FeatureConfigManager.excute()`（手机引擎）实证两条关键开关：
     *  - `DISABLE_RESOLUTION_OPTION = 1` → `Recorder.useVehicleResolution = true`：
     *    引擎不再按宽度把 98311 上报的车机分辨率分档（1412 会被压成 1280×720），
     *    而是直接用 `mVehiclWidth × mVehiclHigh`（即 98311 的真实宽高）编码，
     *    **21:9 车机不再被横向拉伸**。
     *  - `MEDIA_LYRIC_SUPPORT = 1` → 引擎 dispatch `carlife_other_media_lyric_support`，
     *    开启歌词支持。
     */
    fun featureConfig(
        connectType: Int = CONNECT_TYPE_WIFI,
        iFrameInterval: Int = I_FRAME_INTERVAL_DEFAULT,
        useVehicleResolution: Boolean = true,
        mediaLyricSupport: Boolean = true,
    ): ByteArray {
        val items = ArrayList<ByteArray>()
        items += pbString(1, "I_FRAME_INTERVAL") + pbVarint(2, iFrameInterval)
        items += pbString(1, "CONNECT_TYPE") + pbVarint(2, connectType)
        items += pbString(1, "DISABLE_RESOLUTION_OPTION") + pbVarint(2, if (useVehicleResolution) 1 else 0)
        items += pbString(1, "MEDIA_LYRIC_SUPPORT") + pbVarint(2, if (mediaLyricSupport) 1 else 0)
        var list = ByteArray(0)
        for (item in items) list += pbBytes(2, item)
        val cnt = items.size
        return pbVarint(1, cnt) + list + pbVarint(3, 0) + pbString(4, "CarPToCarL") + pbString(5, "00:11:22:33:44:55")
    }

    /**
     * 车机 → 手机的编码参数申请（98311）。
     *
     * 8.8.5 的 `CarlifeVideoEncoderInfo` 比旧版 proto 多 6 个字段（逆向 `writeTo` 实证）：
     * ```
     * 1 width  int32 | 2 height int32 | 3 frameRate int32
     * 4 saveAreaWidth int32 | 5 saveAreaHeight int32 | 6 saveAreaX int32 | 7 saveAreaY int32
     * 8 drawUIOutsideSafeArea bool | 9 needAdapterScreen bool
     * ```
     * 这些不是装饰：`MediaFormatUtils.setVendorEncoderParams` 会把它们打包进
     * `dispatcherAction("carlife_encode_format", map)` 交给**厂商适配层（荣耀 mix 模块）**，
     * 由厂商层决定最终 `MediaFormat` 的 width/height（返回非 null 时覆盖引擎自己算的值）。
     * 所以只发 width/height 时，厂商层拿不到「车机真实画布 / 安全区 / 是否需适配」，
     * 会按自己的规则回 1280×720 → 21:9 车机被横向拉伸（2026-10-25 实测）。
     *
     * @param needAdapterScreen 车机画面比例与编码比例不一致时请引擎做适配（等比，不拉伸）
     * @param saveArea 车机安全区 [宽, 高, x, y]；null 表示不声明
     */
    fun videoEncoderInfo(
        width: Int,
        height: Int,
        fps: Int,
        needAdapterScreen: Boolean = false,
        saveArea: IntArray? = null,
        drawUIOutsideSafeArea: Boolean = false,
    ): ByteArray {
        var out = pbVarint(1, width) + pbVarint(2, height) + pbVarint(3, fps)
        if (saveArea != null && saveArea.size == 4) {
            out += pbVarint(4, saveArea[0]) +
                pbVarint(5, saveArea[1]) +
                pbVarint(6, saveArea[2]) +
                pbVarint(7, saveArea[3])
        }
        if (drawUIOutsideSafeArea) out += pbVarint(8, 1)
        if (needAdapterScreen) out += pbVarint(9, 1)
        return out
    }

    /** `CarlifeVideoFrameRate{frameRate = 1}`。 */
    fun videoFrameRate(fps: Int): ByteArray = pbVarint(1, fps)

    /** 模块状态列表回复：把每个模块状态置 1（开启）。 */
    fun moduleControl(moduleIds: List<Int>): ByteArray {
        var out = pbVarint(1, moduleIds.size)
        for (id in moduleIds) {
            out += pbBytes(2, pbVarint(1, id) + pbVarint(2, 1))
        }
        return out
    }

    /**
     * 单点触控负载 `CarlifeTouchSinglePoint{x=1, y=2}`（proto 里只有这两个字段）。
     * 注意动作不在负载里——DOWN/MOVE/UP 分别用 [TOUCH_DOWN]/[TOUCH_MOVE]/[TOUCH_UP]。
     */
    fun touchPoint(
        x: Int,
        y: Int,
    ): ByteArray = pbVarint(1, x) + pbVarint(2, y)

    /**
     * 复合动作负载 `CarlifeTouchAction{action=1, x=2, y=3}`。
     *
     * `action` 是**原始 MotionEvent 动作码**，指针索引用 Android 的高字节约定：
     * 第二根手指按下 = `ACTION_POINTER_DOWN or (1 shl 8)` = 261，抬起 = 262。
     * 这是官方 HU SDK「新触摸方式」（`TouchListenerManager.sendAction`）的用法，
     * 也是这条通道里**唯一能表达「哪根手指」**的地方——单点消息（0x68002/3/4）
     * 的负载只有 x/y，多点手势靠它无从表达，这正是「双指按不出来」的根因。
     */
    fun touchAction(
        action: Int,
        x: Int,
        y: Int,
    ): ByteArray = pbVarint(1, action) + pbVarint(2, x) + pbVarint(3, y)

    /** 解析 65574 模块状态列表，返回 (moduleId, status) 列表。 */
    fun parseModuleStatus(payload: ByteArray): List<Pair<Int, Int>> {
        val result = ArrayList<Pair<Int, Int>>()
        var index = 0
        while (index < payload.size) {
            val tag = payload[index].toInt() and 0xFF
            index++
            if (tag == 0x08) {
                // cnt，跳过
                while (index < payload.size && (payload[index].toInt() and 0x80) != 0) index++
                index++
            } else if (tag == 0x12) {
                val length = payload[index].toInt() and 0xFF
                index++
                val end = (index + length).coerceAtMost(payload.size)
                var moduleId = 0
                var status = 0
                var cursor = index
                while (cursor < end) {
                    val subTag = payload[cursor].toInt() and 0xFF
                    cursor++
                    when (subTag) {
                        0x08 -> moduleId = payload[cursor].toInt() and 0xFF
                        0x10 -> status = payload[cursor].toInt() and 0xFF
                        else -> break
                    }
                    cursor++
                }
                result += moduleId to status
                index = end
            } else {
                break
            }
        }
        return result
    }

    /**
     * 解析 0x10035 媒体信息载荷，兼容两种实际存在的封装。
     *
     * - **格式 A（协议文档）**：外层 `CarlifeMediaInfoList`——cnt(tag 0x08) + repeated
     *   mediaInfo(tag 0x12, length-delimited 子消息)。
     * - **格式 B（本机引擎实测）**：**直接推 `CarlifeMediaInfo` 本体，无 List 包装**，
     *   首 tag 为 0x0a（field 1，wireType 2）。
     *
     * 旧实现只认格式 A，遇到 0x0a 落到 `else -> return` 立即退出，实测 217/217 全部
     * 解析为空——歌名、歌手、封面、歌词全部丢失（2026-10-30 日志仍是
     * 「收到媒体信息（22143B）但解析为空」每 2 秒一条）。先按 A 试，拿不到再按 B。
     *
     * 来源：分支 fix/audio-tts-and-media-metadata（af340ce，2026-10-29 真机实测）。
     */
    fun parseMediaInfoList(payload: ByteArray): List<CarLifeMediaInfo> {
        val wrapped = parseMediaInfoListWrapped(payload)
        if (wrapped.isNotEmpty()) return wrapped
        val single = parseMediaInfo(payload, 0, payload.size)
        return if (single != null) listOf(single) else emptyList()
    }

    /** 格式 A：外层 `CarlifeMediaInfoList`（cnt + repeated mediaInfo 子消息）。 */
    private fun parseMediaInfoListWrapped(payload: ByteArray): List<CarLifeMediaInfo> {
        val result = ArrayList<CarLifeMediaInfo>()
        var index = 0
        while (index < payload.size) {
            val tag = payload[index].toInt() and 0xFF
            index++
            when (tag) {
                0x08 -> {
                    // cnt（varint），跳过即可
                    index = skipVarint(payload, index)
                }

                0x12 -> {
                    // mediaInfo 子消息（length-delimited）
                    val length = readVarint(payload, index)
                    index += varintLen(payload, index)
                    val end = (index + length).coerceAtMost(payload.size)
                    parseMediaInfo(payload, index, end)?.let { result += it }
                    index = end
                }

                else -> {
                    // 未知字段：无法安全跳过，停止解析（保护性退出）
                    return result
                }
            }
        }
        return result
    }

    /**
     * 解析单个 `CarlifeMediaInfo`；歌名与歌手都为空时返回 null。
     *
     * **字段语义以 2026-10-29 真机实测为准，旧实现的映射是整体错位的。**
     * 实测环境：小米 M610BB（HyperOS / Android 17）+ 本机 CarLife+，播放源 QQ音乐。
     *
     * | 字段 | 真实语义 | 实测样例 |
     * |---|---|---|
     * | 1 | 媒体 ID | `1793209917751` |
     * | 2 | **当前歌词行**（旧代码当成歌名！） | `为你解冻冰河` |
     * | 3 | **「歌名-歌手」合并串**（旧代码当歌手！） | `有何不可-许嵩` |
     * | 4 | 专辑 | `天外来物` |
     * | 5 | 封面 JPEG（`FF D8 FF E0 …JFIF`） | 2817B / 18203B |
     * | 6 | 时长，**单位秒**（旧代码当毫秒！） | 241（=4:01） |
     * | 7 | 播放列表序号 | 0 |
     * | 8 | 歌曲 ID | `241840` |
     * | 9 | 播放模式 | 0 |
     *
     * 会收到歌词是因为本程序在 98386 里声明了 `MEDIA_LYRIC_SUPPORT=1`，QQ音乐便把当前
     * 歌词行塞进 field 2 并逐行刷新——这就是 0x10035 一首歌推两百多次的原因。
     */
    private fun parseMediaInfo(
        payload: ByteArray,
        start: Int,
        end: Int,
    ): CarLifeMediaInfo? {
        var source = ""
        var lyricRaw = ""
        var titleArtistRaw = ""
        var album = ""
        var albumArt: ByteArray? = null
        var duration = 0
        var playlistNum = 0
        var songId = ""
        var mode = 0
        var index = start
        while (index < end) {
            val tag = payload[index].toInt() and 0xFF
            index++
            val field = tag ushr 3
            val wireType = tag and 0x07
            when {
                // string/bytes（length-delimited, wireType=2）
                wireType == 2 -> {
                    val length = readVarint(payload, index)
                    index += varintLen(payload, index)
                    val valueEnd = (index + length).coerceAtMost(end)
                    val bytes = payload.copyOfRange(index, valueEnd)
                    when (field) {
                        1 -> source = bytes.toString(Charsets.UTF_8)
                        2 -> lyricRaw = bytes.toString(Charsets.UTF_8)
                        3 -> titleArtistRaw = bytes.toString(Charsets.UTF_8)
                        4 -> album = bytes.toString(Charsets.UTF_8)
                        5 -> albumArt = bytes
                        8 -> songId = bytes.toString(Charsets.UTF_8)
                    }
                    index = valueEnd
                }

                // int32（varint, wireType=0）
                wireType == 0 -> {
                    val value = readVarint(payload, index)
                    index += varintLen(payload, index)
                    when (field) {
                        6 -> duration = value
                        7 -> playlistNum = value
                        9 -> mode = value
                    }
                }

                // wireType=5 (fixed32) 或未知：跳过固定长度/放弃
                else -> return null
            }
        }
        val (song, artist) = splitTitleArtist(titleArtistRaw)
        if (song.isBlank() && artist.isBlank()) return null
        return CarLifeMediaInfo(
            source = source,
            song = song,
            artist = artist,
            album = album,
            albumArt = albumArt,
            duration = duration,
            playlistNum = playlistNum,
            songId = songId,
            mode = mode,
            lyric = lyricRaw,
        )
    }

    /**
     * 把 field 3 的「歌名-歌手」合并串拆开。
     *
     * QQ音乐实测：`有何不可-许嵩`、`春娇与志明-街道办GDC/欧阳耀莹`。按第一个 `-` 拆；
     * 没有 `-` 时整串当歌名。
     *
     * 已知小瑕疵（沿用参考分支的观察）：QQ音乐偶尔先推一条不含 `-` 的纯歌手串，
     * 这一瞬歌名会被填成歌手名，约 0.1 秒后的下一条自动纠正——去重指纹按
     * song|artist|album|封面大小 计算，不会因此刷屏。
     */
    private fun splitTitleArtist(raw: String): Pair<String, String> {
        val text = raw.trim()
        if (text.isEmpty()) return "" to ""
        val dash = text.indexOf('-')
        if (dash <= 0 || dash == text.lastIndex) return text to ""
        return text.substring(0, dash).trim() to text.substring(dash + 1).trim()
    }

    /**
     * 诊断：把 0x10035 载荷按 protobuf wire 格式逐字段描述成一行。
     *
     * 用途是**拿真实报文校验字段语义**。field 2=歌词 / 3=「歌名-歌手」/ 6=秒 这套映射
     * 来自另一分支的真机结论，本机引擎版本未必一致；每次给出原始视图即可当场核对：
     * 字符串给前 24 字、字节给长度与 JPEG 魔数判定、整型给数值。
     */
    fun describeMediaInfo(payload: ByteArray, maxFields: Int = 12): String {
        val out = StringBuilder()
        var index = 0
        var count = 0
        while (index < payload.size && count < maxFields) {
            val tag = payload[index].toInt() and 0xFF
            index++
            val field = tag ushr 3
            val wireType = tag and 0x07
            when (wireType) {
                2 -> {
                    val length = readVarint(payload, index)
                    index += varintLen(payload, index)
                    val end = (index + length).coerceAtMost(payload.size)
                    val bytes = payload.copyOfRange(index, end)
                    val isJpeg = bytes.size >= 3 &&
                        (bytes[0].toInt() and 0xFF) == 0xFF &&
                        (bytes[1].toInt() and 0xFF) == 0xD8
                    val preview =
                        when {
                            isJpeg -> "<JPEG ${length}B>"
                            bytes.isEmpty() -> "<空>"
                            else -> bytes.toString(Charsets.UTF_8)
                                .replace('\n', ' ')
                                .take(24)
                        }
                    out.append("f$field=[").append(preview).append("] ")
                    index = end
                }

                0 -> {
                    val value = readVarint(payload, index)
                    index += varintLen(payload, index)
                    out.append("f$field=").append(value).append(" ")
                }

                else -> {
                    out.append("f$field=<wire$wireType 未支持>")
                    break
                }
            }
            count++
        }
        return out.toString().trim()
    }

    /**
     * 解析媒体进度条（`MD_MEDIA_PROGRESS`）载荷为毫秒播放位置。
     *
     * 载荷是 protobuf：`[0x08][varint progressMs]`。
     *
     * 旧实现把前 4 字节按 u32 大端读，把 tag `0x08` 也当成了数据高位——实测值恒落在
     * `0x08000000`~`0x08FFFFFF`（134.2M~151.0M，≈41 小时），且随 varint 低位来回跳。
     * 按 varint 解码（`08 AE CE 09` → 157486）才与播放位置吻合。
     */
    fun parseMediaProgress(payload: ByteArray): Int {
        var index = 0
        while (index < payload.size) {
            val tag = payload[index].toInt() and 0xFF
            index++
            val field = tag ushr 3
            val wireType = tag and 0x07
            when (wireType) {
                0 -> {
                    val value = readVarint(payload, index)
                    index += varintLen(payload, index)
                    if (field == 1) return value.coerceAtLeast(0)
                }

                2 -> {
                    val length = readVarint(payload, index)
                    index += varintLen(payload, index) + length
                }

                else -> return 0
            }
        }
        return 0
    }

    private fun readVarint(
        payload: ByteArray,
        index: Int,
    ): Int {
        var result = 0
        var shift = 0
        var i = index
        while (i < payload.size && shift < 35) {
            val b = payload[i].toInt() and 0xFF
            result = result or ((b and 0x7F) shl shift)
            i++
            if (b and 0x80 == 0) break
            shift += 7
        }
        return result
    }

    private fun varintLen(
        payload: ByteArray,
        index: Int,
    ): Int {
        var len = 0
        var i = index
        while (i < payload.size && len < 5) {
            len++
            if (payload[i].toInt() and 0x80 == 0) break
            i++
        }
        return len
    }

    private fun skipVarint(
        payload: ByteArray,
        index: Int,
    ): Int = index + varintLen(payload, index)

    private fun writeU16(target: ByteArray, offset: Int, value: Int) {
        target[offset] = ((value ushr 8) and 0xFF).toByte()
        target[offset + 1] = (value and 0xFF).toByte()
    }

    private fun writeU32(target: ByteArray, offset: Int, value: Int) {
        target[offset] = ((value ushr 24) and 0xFF).toByte()
        target[offset + 1] = ((value ushr 16) and 0xFF).toByte()
        target[offset + 2] = ((value ushr 8) and 0xFF).toByte()
        target[offset + 3] = (value and 0xFF).toByte()
    }
}

/** CarLife 车机侧收到的一条媒体信息（0x10035 载荷的单个元素）。 */
data class CarLifeMediaInfo(
    val source: String,
    /** 歌名（由 field 3 的「歌名-歌手」按第一个 '-' 拆出）。 */
    val song: String,
    /** 歌手（field 3 的 '-' 之后部分；无 '-' 时为空）。 */
    val artist: String,
    val album: String,
    val albumArt: ByteArray?,
    /** 时长，**单位秒**（协议如此，实测 QQ音乐 204 = 3:24）。 */
    val duration: Int,
    val playlistNum: Int,
    val songId: String,
    val mode: Int,
    /** 当前歌词行（field 2，逐行刷新；iAP2 0x5001 暂无字段可承载，仅留存）。 */
    val lyric: String = "",
)
