package io.github.shaonialife.carptocar.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import io.github.shaonialife.carptocar.airplay.AirPlayCrypto
import io.github.shaonialife.carptocar.airplay.TimingServer
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean

/**
 * CarPlay 主音频发送端（手机 → 车机，RTP over UDP）。
 *
 * 线格式（对照 DiPlay `AudioStream.kt` 解密路径与 catplay `rtp_cipher_chacha.rs`）：
 * ```
 * [RTP 头 12B][ChaCha20-Poly1305 密封的 PCM 负载 + 16B tag][8B LE nonce]
 * ```
 *  - 加密：ChaCha20-Poly1305；AAD = 头 4..12 字节（timestamp+ssrc 的 BE 表示，
 *    catplay 的 "modern AAD"；DiPlay 解密即取同一范围）
 *  - nonce（12B）= 4 字节零 + 尾 8 字节（小端计数，从 0 逐包递增）→ [AirPlayCrypto.nonce64]
 *  - RTP 字段：version=2、PT=流类型（100=PCM 通路 / 102=AAC-LC 通路；
 *    对照 catplay `payload_type = stream_type`）、
 *    SSRC=0（catplay 同款）、timestamp = 累计样本帧数、seq 逐包递增
 *  - **首个音频包置 Marker 位**（规范：RECORD/FLUSH 之后的首包要打标记，接收端据此
 *    切分播放边界；catplay 恒 false，但我们的会话是「RECORD 之后才加入音频流」，
 *    首包标记能帮接收端重新对齐）
 *
 * 旁路：
 *  - **同步包**：[AudioSyncPacket] 每 1 秒发往该流的 `controlPort`，建立 RTP↔NTP 锚点。
 *    （缺了它接收端只能「按到达时刻猜」，严格的实现干脆不排播——2026-10-20 实测）
 *
 * 采集：`AudioPlaybackCapture`（Android 10+）捕获手机端其他应用播放的媒体/导航音频
 * （需要 MediaProjection——与镜像模式的同一授权）。每包 [SAMPLES_PER_PACKET] 样本帧
 * （48000Hz 立体声 16bit = 2048B 负载，总包 ~2084B，不超接收端 4096B 收包缓冲）。
 */
class AudioSender(
    private val host: String,
    private val dataPort: Int,
    private val streamKey: ByteArray,
    private val log: (String) -> Unit,
    /** 静音手机媒体音量（声音只从车机出）。null = 不做（测试用）。 */
    private val context: android.content.Context? = null,
    /**
     * 采集采样率（Hz）：必须与 SETUP 协商出的格式一致（48k 主选，车机只支持
     * 44.1k 时由 AirPlayClient 协商下发）。默认 48k。
     */
    private val sampleRate: Int = 48_000,
    /** 负载编码：PCM（type=100 通路）或 AAC-LC（type=102 通路）。 */
    private val codec: AudioCodec = AudioCodec.PCM,
    /** RTP 流类型，仅用于日志（PT 与 SETUP 的 type 保持一致）。 */
    private val streamType: Long = 100L,
    /** AAC 帧是否补 ADTS 头（见 [AudioTuning.ADTS]，排查车机解码链用）。 */
    private val adtsFrames: Boolean = false,
    /**
     * 该音频流的控制端口（车机在 SETUP 应答里下发）。>0 时每 [SYNC_INTERVAL_MILLIS]
     * 发一个 [AudioSyncPacket] 建立 RTP↔NTP 锚点；0 = 车机没给（旧车机），只发音频。
     */
    private val controlPort: Int = 0,
    /**
     * 目标声道数。默认 2（PCM/AAC 媒体通路）；[AudioCodec.OPUS] 必须传 1。
     * 见 [AudioProfile.channels] 的说明。
     */
    private val channels: Int = CHANNELS,
    /**
     * OPUS 目标码率（bps），仅 [AudioCodec.OPUS] 生效。
     *
     * Apple 自己的低延迟流码率档位（MacPlay `cpStack.js` 记录）：
     *   ≤24kHz → 48kbps，≤32kHz → 64kbps，**48kHz → 96kbps**
     *
     * 2026-10-29 从 24k 提到 96k：48kHz fullband 模式要编码 0–20kHz 带宽，
     * 24kbps 摊下去语音频段严重不足，编码器被迫大量丢细节——表现正是
     * 「包没丢、抖动 1.26ms、解码 100%，但听起来断续」。96k 仍只有 PCM 的 1/16。
     */
    private val opusBitRate: Int = 96_000,
    /**
     * OPUS 编码器内部率（Hz），仅 [AudioCodec.OPUS] 生效。
     *
     * 默认 48000（与 RTP 时钟一致，旧行为：16k 源先上采样到 48k 再编）。
     * 设 16000 即「原生 16k 编码」——让 Opus 直接吃 16k 导航 TTS，
     * 跳过 [alignPcm] 的 16k→48k 上采样与抗成像低通（成像噪声/亮度损失的来源）。
     *
     * 注意 RTP 时钟仍锁定 48k（RFC 7587）：时间戳步进恒为 960 样本/帧，
     * 与这里填 48k 还是 16k **无关**——[samplesPerPacket] 对 OPUS 永远取
     * [OpusEncoder.FRAME_SAMPLES]=960。所以原生 16k 不会让车机变速。
     *
     * 默认由调用方按 settings.alt_opus_native 决定（SessionController），
     * 这里只是给一个安全的 48k 兜底值。
     */
    private val opusInternalRate: Int = 48_000,
    /**
     * RTP 冗余帧级数（RFC 2198）。0 = 关闭（默认，行为完全不变）。
     * 1..7 = 每包额外携带最近 N 个历史帧的副本，抗 WiFi 丢包——
     * 丢一包时接收端能从后续包里恢复，避免车内 WiFi 抖动爆音/断字。
     *
     * 代价：每包变大（≈ 1+级数 倍），且 SETUP 必须同时声明
     * `supportsRTPPacketRedundancy=true`（见 [airplay.AirPlayClient.runAudioStream]）。
     * **仅对压缩编码（AAC/OPUS）生效**：PCM 一帧 48k 立体声 ≈ 1.9KB，翻倍即超
     * 1500B MTU 会被分片/丢弃，所以 PCM 通路强制不走冗余。
     *
     * 默认 0（关闭）是为了安全：此格式必须与车机严格一致，开启后需真机验证；
     * 未开启时一切沿用旧路径。
     */
    private val rtpRedundancyLevels: Int = 0,
) {
    companion object {
        const val CHANNELS = 2

        /**
         * 每包样本帧数 = AirPlay 标准 SPF 352（catplay `MAX_FRAMES_PER_PACKET_PCM`，
         * 真车验证）。48000/352 ≈ 136 包/秒（7.33ms/包）。
         * 2026-10-17 实测：此前用 512 帧（10.67ms/包），奥迪接收端无声音——
         * 与真 iPhone 的包长不一致时接收端可能按固定 SPF 缓冲而丢弃。
         */
        const val SAMPLES_PER_PACKET = 352

        /** 一包负载字节数（16bit 立体声）。 */
        const val PACKET_BYTES = SAMPLES_PER_PACKET * CHANNELS * 2

        /** 同步包发送间隔（规范：每秒一个）。 */
        const val SYNC_INTERVAL_MILLIS = 1_000L

        private const val RTP_HEADER_LEN = 12
        private const val TAG_SIZE = 16
        private const val NONCE_SIZE = 8

        private const val READ_TIMEOUT_QUIET_MILLIS = 500L

        /** 静音前媒体音量的持久化键（进程被杀也不丢恢复目标）。 */
        private const val KEY_SAVED_VOLUME = "audio_saved_media_volume"

        /** 小端 s16 → 大端 s16（每两字节换序）。CarPlay 线格式要求大端样本。 */
        fun bigEndianS16(source: ByteArray): ByteArray {
            val out = source.copyOf()
            var index = 0
            while (index + 1 < out.size) {
                val tmp = out[index]
                out[index] = out[index + 1]
                out[index + 1] = tmp
                index += 2
            }
            return out
        }
    }

    private val running = AtomicBoolean(false)

    /** stop() 幂等闸门（多条收尾路径都会调 stop）。 */
    private val stopped = AtomicBoolean(false)
    private var socket: DatagramSocket? = null
    private var syncSocket: DatagramSocket? = null
    private var record: AudioRecord? = null
    private var thread: Thread? = null
    private var syncThread: Thread? = null

    /** 同步包序号（跨重试连续，避免车机把重发当成乱序丢掉）。 */
    private var syncSequence = 0

    /** 同步包连续失败次数（诊断 + 决定何时重开套接字）。 */
    private var syncFailures = 0

    /** 首个同步包（X 位=1）是否已成功发出。 */
    private var syncFirstSent = false

    /** AAC-LC 编码器（仅 [AudioCodec.AAC_LC] 时创建）。 */
    private var aacEncoder: AacLcEncoder? = null

    /** OPUS 编码器（仅 [AudioCodec.OPUS] 时创建）。 */
    private var opusEncoder: OpusEncoder? = null

    /**
     * RTP 游标（原本是采集循环里的局部变量）。提到字段上是因为**同步包线程**要读
     * 「当前/下一包时间戳」，否则锚点里的时间戳永远对不上真实发出的包。
     */
    @Volatile private var sequence = 0

    @Volatile private var timestamp = 0L

    @Volatile private var nonceCounter = 0L

    /**
     * 抗成像低通的跨包状态：每声道两级 biquad 各 2 个状态变量。
     * 必须跨包保持，否则包边界不连续会产生规律性咔哒声。
     */
    private val antiImageState = Array(channels) { DoubleArray(4) }

    /** 当前低通截止频率（按源采样率缓存；变化时重算系数并清状态）。 */
    private var antiImageFc = 0.0

    /** 当前低通系数 `[b0, b1, b2, a1, a2]`，null = 还没算过。 */
    private var antiImageCoeffs: DoubleArray? = null

    /** 滤波器状态被重置的次数（>1 说明源采样率在变，会引入瞬态咔哒）。 */
    private var filterResets = 0

    /**
     * RTP 冗余用的历史帧环形缓冲：每条 = (已编码负载, 该包 RTP 时间戳)。
     * 仅缓存最近 [rtpRedundancyLevels] 条，供后续包作为冗余块重发。
     * 只用压缩编码（AAC/OPUS）的负载，PCM 不通冗余。
     */
    private data class RedEntry(val payload: ByteArray, val ts: Long)

    private val redHistory =
        if (rtpRedundancyLevels > 0) ArrayDeque<RedEntry>(rtpRedundancyLevels + 1) else null
    private var redLogged = false

    // ---- 诊断（2026-10-29）：定位「卡顿 + 沙沙」----
    /** 喂入次数 / 成功发包数 / 源包间隔统计。 */
    private var fedCount = 0
    private var sentCount = 0
    private var lastFeedAt = 0L
    private var gapSum = 0L
    private var gapMax = 0L
    private var gapCount = 0

    /** 音频落盘（白盒分析用）：settings.debug_dump_audio = true 时把源与上采样后 PCM 写文件。 */
    private val dumpEnabled: Boolean by lazy {
        context?.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
            ?.getBoolean("debug_dump_audio", false) ?: false
    }
    private var dumpSeq = 0

    /**
     * 发送均匀化开关（settings.audio_pacing_enabled，默认 true）。
     *
     * 背景：CarLife 一次送一大块（TTS 一包 = 4 个 Opus 帧），原来连着发，
     * 接收端抖动检测会判超窗。均匀化按每帧时长放行，实测把 jitterLost 从 9 降到 0。
     *
     * 但它是在 dispatchPcm → sendPayload 路径上 sleep，会**阻塞喂数据的线程**。
     * 若上游持续快于实时，延迟会累积。留这个开关便于对比「有/无均匀化」的听感。
     */
    private val pacingEnabled: Boolean by lazy {
        context?.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
            ?.getBoolean("audio_pacing_enabled", true) ?: true
    }

    private fun dumpPcm(tag: String, data: ByteArray, size: Int) {
        if (!dumpEnabled || dumpSeq >= 80) return
        val dir = context?.getExternalFilesDir(null) ?: return
        try {
            java.io.File(dir, "tts-$tag-$dumpSeq.bin")
                .writeBytes(if (size == data.size) data else data.copyOf(size))
        } catch (_: Throwable) {
            // 落盘失败不影响播放
        }
    }

    /**
     * 下一包的「理论发送时刻」（uptimeMillis），用于**发送均匀化**。
     *
     * 为什么需要（2026-10-29 MacPlay 实测）：CarLife 送来的音频是**大块**——
     * 一包导航 TTS = 2560B = 80ms 音频，重采样后正好 4 个 Opus 帧；媒体也常几帧
     * 一起到。直通路径原来一拿到就 `for (payload) send()` 连发，4 个 UDP 包间隔≈0，
     * 而它们的 RTP 时间戳各差 20ms。接收端抖动缓冲按「包间隔」排播，瞬时突发会被
     * 判超窗丢弃：实测 Opus jitterLost=9/3367、AAC jitterLost=22/27381。
     *
     * 现在按每包的真实时长（samplesPerPacket / sampleRate）均匀放行，
     * 接收端看到的就是稳定节奏。0 = 尚未起锚。
     */
    private var nextSendAt = 0L

    /** 首个音频包标记（打完就清）；AAC 自检只跑一次。 */
    private var markerPending = true
    private var selfChecked = false

    /** 每包/每帧对应的 PCM 字节数（PCM 通路=一包的负载；AAC 通路=一帧的输入）。 */
    private val framePcmBytes: Int
        get() =
            when (codec) {
                AudioCodec.AAC_LC -> AacLcEncoder.pcmBytes(channels)
                AudioCodec.OPUS -> OpusEncoder.pcmBytes(opusInternalRate)
                else -> samplesPerPacket.toInt() * channels * 2
            }

    /** 每包推进的样本数（AAC 一帧 1024，PCM 一包 352）。 */
    private val samplesPerPacket: Long
        get() =
            when (codec) {
                AudioCodec.AAC_LC -> AacLcEncoder.FRAME_SAMPLES.toLong()
                AudioCodec.OPUS -> OpusEncoder.FRAME_SAMPLES.toLong()
                else -> SAMPLES_PER_PACKET.toLong()
            }

    /**
     * RTP SSRC：固定 0（catplay `prepare_header` 在真车上即用 0；DiPlay 收端不校验）。
     * 2026-10-17 实测：此前用随机值，奥迪接收端无声音——部分车机按 SSRC 关联 SETUP
     * 声明的流（非 0 视为未知流丢弃）。
     */
    private val ssrc = 0

    /** 采集+推流统计（日志限流用）。 */
    private var packetsSent = 0L
    private var bytesSent = 0L
    private var lastLogAt = 0L
    private var readFailures = 0

    val isRunning: Boolean get() = running.get()

    /** 静音前的手机媒体音量 index（-1 = 未静音）。 */
    private var mutedOriginalVolume = -1

    /**
     * 静音手机媒体输出：音频通道激活后声音只从车机出（真 CarPlay 行为）。
     * 依据（实测 2026-10-10）：播放捕获取自 REMOTE_SUBMIX（音量应用点之前），
     * 手机流音量静音**不影响捕获数据**——手机不响、车机照常出声。
     *
     * 恢复值持久化（SharedPreferences）：进程被系统杀死/安装更新时来不及恢复，
     * 下次静音若读到 0 会把它当"原音量"，导致断开后永久静音（2026-10-10 踩过）。
     */
    private fun mutePhoneMedia() {
        val ctx = context ?: return
        try {
            val manager =
                ctx.getSystemService(android.content.Context.AUDIO_SERVICE)
                    as android.media.AudioManager
            if (mutedOriginalVolume >= 0) return
            val prefs = ctx.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
            val saved = prefs.getInt(KEY_SAVED_VOLUME, -1)
            val current = manager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
            // 目标恢复值：优先上次未恢复成功的保存值；否则当前值（>0 才可信）
            val restoreTarget =
                when {
                    saved > 0 -> saved
                    current > 0 -> current
                    else -> -1
                }
            if (restoreTarget > 0) {
                prefs.edit().putInt(KEY_SAVED_VOLUME, restoreTarget).apply()
            }
            mutedOriginalVolume = restoreTarget
            manager.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, 0, 0)
            // 回读校验：ROM 的音频策略可能拒绝/立刻回弹（实测见过 volume 仍为原值），
            // 此时手机继续外放——必须让日志能直接看出来
            val after = manager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
            log(
                "AUDIO: 已静音手机媒体输出（原音量 ${if (restoreTarget > 0) restoreTarget else "未知"}，" +
                    "断开后恢复）——回读音量=$after" +
                    if (after != 0) "（⚠ 静音未生效，手机仍会外放）" else "",
            )
        } catch (error: Exception) {
            log("AUDIO: 静音手机媒体失败 - ${error.message}")
        }
    }

    private fun restorePhoneMedia() {
        val ctx = context ?: return
        try {
            val manager =
                ctx.getSystemService(android.content.Context.AUDIO_SERVICE)
                    as android.media.AudioManager
            val prefs = ctx.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
            val target =
                when {
                    mutedOriginalVolume > 0 -> mutedOriginalVolume
                    else -> prefs.getInt(KEY_SAVED_VOLUME, -1)
                }
            if (target > 0) {
                manager.setStreamVolume(
                    android.media.AudioManager.STREAM_MUSIC,
                    target,
                    0,
                )
                log("AUDIO: 已恢复手机媒体音量（$target）")
            } else {
                log("AUDIO: 无有效原音量记录，手机媒体保持当前值（请按音量键调整）")
            }
            prefs.edit().remove(KEY_SAVED_VOLUME).apply()
        } catch (_: Exception) {
        }
        mutedOriginalVolume = -1
    }

    /**
     * 启动音频发送。需要 MediaProjection（AudioPlaybackCapture 的授权来源）。
     * 返回是否成功进入采集循环。
     */
    fun start(projection: MediaProjection): Boolean {
        if (running.get()) return true
        stopped.set(false)
        return try {
            val client = DatagramSocket()
            client.connect(InetAddress.getByName(host), dataPort)
            socket = client

            // AAC 通路先把编码器立起来：编不出来就没必要采集（也避免「有包无声」的假象）
            if (codec == AudioCodec.AAC_LC) {
                val encoder = AacLcEncoder(sampleRate, CHANNELS, wrapAdts = adtsFrames, log = log)
                if (!encoder.start()) {
                    client.close()
                    socket = null
                    return false
                }
                aacEncoder = encoder
            }

            val captureConfig = buildCaptureConfig(projection)
            val audioFormat =
                AudioFormat
                    .Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                    .build()
            val recorder =
                AudioRecord
                    .Builder()
                    .setAudioPlaybackCaptureConfig(captureConfig)
                    .setAudioFormat(audioFormat)
                    .setBufferSizeInBytes(framePcmBytes * 8)
                    .build()
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                log("AUDIO: AudioRecord 初始化失败（state=${recorder.state}）")
                runCatching { recorder.release() }
                aacEncoder?.stop()
                aacEncoder = null
                client.close()
                socket = null
                return false
            }
            record = recorder
            running.set(true)
            mutePhoneMedia()
            log(
                "AUDIO: 已启动（播放捕获 ${sampleRate}Hz 立体声 → $host:$dataPort，" +
                    "通路=t$streamType/${codec.name}，" +
                    if (codec == AudioCodec.AAC_LC) {
                        "每包 1 个 AAC 帧（${AacLcEncoder.FRAME_SAMPLES} 采样" +
                            (if (adtsFrames) "，ADTS 封装））" else "，裸帧））")
                    } else {
                        "每包 ${SAMPLES_PER_PACKET} 帧 PCM）"
                    },
            )

            // 同步包线程先起：锚点越早建立，接收端越早能排播
            startSyncLoop()

            Thread(
                {
                    try {
                        recorder.startRecording()
                    } catch (error: Exception) {
                        log("AUDIO: 启动采集失败 - ${error.message}")
                        stop()
                        return@Thread
                    }
                    runCaptureLoop()
                },
                "audio-capture",
            ).apply {
                isDaemon = true
                start()
                thread = this
            }
            true
        } catch (error: Exception) {
            log("AUDIO: 启动失败 - ${error.message}")
            stop()
            false
        }
    }

    /**
     * 启动**直通模式**音频发送（无需 MediaProjection / 播放捕获授权）。
     *
     * 音频数据由外部通过 [feedPcm] 注入（如 CarLife 引擎的裸 PCM），这里只负责
     * 封包（RTP + ChaCha20 密封）与发送——彻底绕开「本机播放 + 播放捕获」中转，
     * 因此不依赖录屏授权，也少一次系统内混音/重采样往返。
     *
     * 采样率/声道对齐由注入方负责（[feedPcm] 会按 [sampleRate]/[CHANNELS] 重采样），
     * 但 AAC 通路仍走 [AacLcEncoder]（注入的是 PCM，编码照常）。
     */
    fun startPassthrough(): Boolean {
        if (running.get()) return true
        stopped.set(false)
        return try {
            val client = DatagramSocket()
            client.connect(InetAddress.getByName(host), dataPort)
            socket = client

            if (codec == AudioCodec.AAC_LC) {
                val encoder = AacLcEncoder(sampleRate, CHANNELS, wrapAdts = adtsFrames, log = log)
                if (!encoder.start()) {
                    client.close()
                    socket = null
                    return false
                }
                aacEncoder = encoder
            } else if (codec == AudioCodec.OPUS) {
                // OPUS 直接吃 [opusInternalRate]（默认 16k 原生）：把 16k 导航 TTS
                // 喂给编码器，不再先上采样到 48k。RTP 时钟仍是 48k（见 [samplesPerPacket]），
                // 所以车机排播节奏不变。码率对齐 Apple 档位（96kbps），见 [opusBitRate]。
                val encoder = OpusEncoder(opusInternalRate, bitRate = opusBitRate, log = log)
                if (!encoder.start()) {
                    client.close()
                    socket = null
                    return false
                }
                opusEncoder = encoder
            }

            running.set(true)
            // 直通模式不静音手机：音频来源是 CarLife 引擎（它已自行采集并推 PCM），
            // 静音 STREAM_MUSIC 可能破坏引擎的采集源（采集点不确定，不能赌是 REMOTE_SUBMIX）。
            log(
                "AUDIO: 已启动直通模式（外部注入 PCM → $host:$dataPort，" +
                    "通路=t$streamType/${codec.name}，目标 ${sampleRate}Hz " +
                    (if (channels == 1) "单声道" else "立体声") + "）",
            )
            startSyncLoop()
            true
        } catch (error: Exception) {
            log("AUDIO: 直通启动失败 - ${error.message}")
            stop()
            false
        }
    }

    /** 直通模式下的帧对齐累积缓冲（把任意大小的注入 PCM 切成固定帧长）。 */
    private val passthroughBuffer = java.io.ByteArrayOutputStream()

    /**
     * 直通模式下由外部注入一段 PCM（16bit 交织）。
     *
     * 会自动把任意采样率/声道数的输入重采样到 [sampleRate]/[CHANNELS]（立体声），
     * 再按固定帧长（PCM 通路=每包 [SAMPLES_PER_PACKET] 采样、AAC 通路=每帧
     * [AacLcEncoder.FRAME_SAMPLES] 采样）累积切帧后走封包/编码/发送。非直通模式
     * （采集循环在跑）时忽略——两条路径不该同时喂数据。
     *
     * @param srcPcm   输入 PCM（16bit 小端交织）
     * @param size     有效字节数
     * @param srcRate  输入采样率（<=0 时按 [sampleRate] 处理，不重采样）
     * @param srcChannels 输入声道数（1 或 2；其它按 2 处理）
     */
    /**
     * 外部注入 PCM（CarLife 直通）。
     *
     * **必须同步**：媒体（9240）与 TTS（9241）是两条独立读线程，2026-10-27 接入导航播报
     * 后两路会同时进来；[passthroughBuffer] 是非线程安全的 ByteArrayOutputStream，
     * 不加锁会出现半帧交错（爆音/破音）甚至丢数据。
     */
    @Synchronized
    fun feedPcm(
        srcPcm: ByteArray,
        size: Int,
        srcRate: Int = 0,
        srcChannels: Int = 2,
    ) {
        if (!running.get() || record != null) return // 采集模式在跑时不接受注入
        if (size <= 0) return
        // 诊断：源包到达节奏。TTS 一包 2560B@16k = 80ms，理想间隔就是 ~80ms；
        // 间隔忽大忽小说明「卡顿」来自 CarLife 源而不是我们的编码/发送。
        val nowAt = android.os.SystemClock.uptimeMillis()
        if (lastFeedAt > 0L) {
            val gap = nowAt - lastFeedAt
            gapSum += gap
            gapCount++
            if (gap > gapMax) gapMax = gap
        }
        lastFeedAt = nowAt
        fedCount++
        dumpPcm("src", srcPcm, size)
        val resampled = alignPcm(srcPcm, size, srcRate, srcChannels) ?: return
        dumpPcm("dst", resampled, resampled.size)
        dumpSeq++
        // 累积到固定帧长，凑满一帧才发（PCM 通路逐包、AAC 通路逐帧）
        val frameBytes = framePcmBytes
        passthroughBuffer.write(resampled, 0, resampled.size)
        while (passthroughBuffer.size() >= frameBytes) {
            val frame = passthroughBuffer.toByteArray().copyOf(frameBytes)
            val rest = passthroughBuffer.toByteArray()
            passthroughBuffer.reset()
            passthroughBuffer.write(rest, frameBytes, rest.size - frameBytes)
            dispatchPcm(frame)
        }
    }

    /**
     * 把一段 PCM 对齐到目标采样率/声道，返回新数组；无需处理时返回原输入。
     *
     * 目标率按通路区分：
     *  - **OPUS**：走 [opusInternalRate]（默认 16k 原生）。16k 源直接透传，
     *    跳过 16k→48k 上采样与抗成像低通——那道多余的上采样正是成像噪声与
     *    亮度损失的来源，应交由 Opus 自己按内部率处理（RFC 7587：RTP 时钟仍 48k）。
     *  - **PCM/AAC**：走协商的 RTP 时钟率 [sampleRate]（通常 48k）。
     *
     * 媒体（44.1k/48k）走原线性插值路径；导航 TTS（16k）在原生模式下零处理。
     */
    private fun alignPcm(
        src: ByteArray,
        size: Int,
        srcRate: Int,
        srcChannels: Int,
    ): ByteArray {
        val inRate = if (srcRate > 0) srcRate else sampleRate
        val inCh = if (srcChannels == 1) 1 else 2
        // OPUS 走**原生内部率**，其余通路走协商的 RTP 时钟率。
        val targetRate = if (codec == AudioCodec.OPUS) opusInternalRate else sampleRate
        val targetCh = if (codec == AudioCodec.OPUS) 1 else channels

        // 声道对齐：单→双（复制）、双→单（取左，导航 TTS 本就单声道内容）。
        var rateAligned: ByteArray
        var rateChannels = inCh
        if (inCh == 1 && targetCh == 2) {
            val monoFrames = size / 2
            val out = ByteArray(monoFrames * 4)
            var i = 0
            var o = 0
            while (i + 1 < size) {
                val lo = src[i]
                val hi = src[i + 1]
                out[o] = lo
                out[o + 1] = hi
                out[o + 2] = lo
                out[o + 3] = hi
                i += 2
                o += 4
            }
            rateAligned = out
            rateChannels = 2
        } else if (inCh == 2 && targetCh == 1) {
            val frames = size / 4
            val out = ByteArray(frames * 2)
            var i = 0
            var o = 0
            while (i + 3 < size) {
                out[o] = src[i]
                out[o + 1] = src[i + 1]
                i += 4
                o += 2
            }
            rateAligned = out
            rateChannels = 1
        } else {
            rateAligned = if (size == src.size) src else src.copyOf(size)
        }

        // 采样率已一致（且声道已对齐）直接返回：OPUS 原生 16k 走这里，跳过全部重采样。
        if (inRate == targetRate) return rateAligned

        // 线性插值重采样（frame = 所有声道的一组采样）
        //
        // 2026-10-29 改进（对齐 origin/main 6b1638b）：原实现是**最近邻取整**
        // （srcPos = frame * inRate / targetRate）。媒体 48k 直通不受影响；原生 16k
        // OPUS 模式下此分支根本不会进入（inRate==targetRate 已提前返回）。
        val inFrames = rateAligned.size / (rateChannels * 2)
        val outFrames = (inFrames.toLong() * targetRate / inRate).toInt()
        val out = ByteArray(outFrames * targetCh * 2)
        val inSamples = rateAligned.size / 2
        val step = inRate.toDouble() / targetRate
        val lastFrame = maxOf(0, inFrames - 1)
        for (frame in 0 until outFrames) {
            val pos = frame * step
            val i0 = pos.toInt().coerceIn(0, lastFrame)
            val i1 = minOf(i0 + 1, lastFrame)
            val frac = pos - i0
            for (c in 0 until targetCh) {
                val srcCh = minOf(c, rateChannels - 1)
                val s0 = readS16(rateAligned, i0 * rateChannels + srcCh, inSamples)
                val s1 = readS16(rateAligned, i1 * rateChannels + srcCh, inSamples)
                val v = (s0 + (s1 - s0) * frac).toInt().coerceIn(-32768, 32767)
                val dst = (frame * targetCh + c) * 2
                out[dst] = (v and 0xFF).toByte()
                out[dst + 1] = ((v shr 8) and 0xFF).toByte()
            }
        }

        // 抗成像低通（仅上采样路径：targetRate > inRate）。
        //
        // 源带宽只到 inRate/2。线性插值把采样率抬到 targetRate 后，inRate/2 之上
        // 会复制出镜像——线性插值核是三角窗，频响 sinc²，16k→48k 时 8kHz 处只
        // 衰减 3.5dB、10kHz 处 5dB，衰减远远不够，听感是持续的「沙沙」高频噪声。
        //
        // 2026-10-29 实测链条：24kbps 时 Opus 把这些高频当噪声大量丢弃，杂音被
        // 掩盖（代价是发闷）；提到 96kbps 后编码器忠实保留，杂音立刻暴露。而
        // MacPlay 侧 packets=580 / decoded=580 / pipelineErrors=0 / invalid=0
        // —— 传输与解码全干净，问题只能在编码前的数据本身。
        //
        // 两级 Butterworth 二阶低通（合计 4 阶、-24dB/oct）在 0.45×inRate 处切。
        // 实测：8–12kHz 镜像相对语音核心 -54.6dB，已在掩蔽阈值以下。
        //
        // 原生 16k 模式下 inRate==targetRate 已提前返回，不会进入此分支；
        // 仅在回落 48k（alt_opus_native=false）时才走滤波。
        if (inRate < targetRate) {
            val fc = inRate * 0.45
            if (fc != antiImageFc) {
                antiImageFc = fc
                antiImageCoeffs = lowpassCoeffs(fc, targetRate)
                // 源采样率变了（切换音源），状态清零避免残留瞬态
                for (st in antiImageState) st.fill(0.0)
                // 显式记一行，免得「到底有没有走滤波」只能靠猜。
                // 同时记录重置次数：若 fc 频繁变化（源采样率抖动），状态会被反复
                // 清零，每次清零都是一次瞬态——那是可闻的「咔哒」。
                filterResets++
                log(
                    "AUDIO-FILTER[$streamType]: 抗成像低通 fc=${fc.toInt()}Hz " +
                        "（${inRate}Hz → ${targetRate}Hz）第 $filterResets 次重置",
                )
            }
            applyAntiImage(out)
        }
        return out
    }

    /**
     * 按截止频率算两级 Butterworth 二阶低通系数。
     * 返回 `[b0, b1, b2, a1, a2]`，已按 a0 归一化（Q = 1/√2 即 Butterworth）。
     */
    private fun lowpassCoeffs(fc: Double, rate: Int = sampleRate): DoubleArray {
        val w0 = 2.0 * Math.PI * fc / rate
        val cosW = Math.cos(w0)
        val alpha = Math.sin(w0) / (2.0 * 0.70710678)
        val a0 = 1.0 + alpha
        val b0 = (1.0 - cosW) / 2.0 / a0
        val b1 = (1.0 - cosW) / a0
        return doubleArrayOf(b0, b1, b0, -2.0 * cosW / a0, (1.0 - alpha) / a0)
    }

    /**
     * 两级串联 biquad 低通，原地处理 16bit 小端交错 PCM。
     *
     * 状态存在 [antiImageState]（每声道 4 个变量，2 级 biquad），**跨包保持**——每包重置会在
     * 包边界产生不连续，听感是规律性的「咔哒」。
     */
    private fun applyAntiImage(out: ByteArray) {
        val c = antiImageCoeffs ?: return
        val b0 = c[0]
        val b1 = c[1]
        val b2 = c[2]
        val a1 = c[3]
        val a2 = c[4]
        val bytesPerFrame = channels * 2
        val frames = out.size / bytesPerFrame
        for (f in 0 until frames) {
            for (ch in 0 until channels) {
                val idx = f * bytesPerFrame + ch * 2
                if (idx + 1 >= out.size) continue
                var x = ((out[idx].toInt() and 0xFF) or (out[idx + 1].toInt() shl 8)).toShort().toDouble()
                val st = antiImageState[ch]
                // 第一级
                var y = b0 * x + st[0]
                st[0] = b1 * x - a1 * y + st[1]
                st[1] = b2 * x - a2 * y
                // 第二级
                x = y
                y = b0 * x + st[2]
                st[2] = b1 * x - a1 * y + st[3]
                st[3] = b2 * x - a2 * y
                // 只做两级（4 阶）。
                //
                // 2026-10-29 回退记录：一度加到三级（6 阶，理论抑制 -66dB），
                // 数值验证完全干净（4 阶峰值 29741 / 6 阶 29759，均无削波，
                // 与真实 dump 逐样本差异 <2），但实测体感反而更差。
                // 既然 4 阶的 -54.6dB 已在语音掩蔽阈值以下，就保持 4 阶：
                // 理论余量换不来听感，少一级也少一半计算。
                val v = y.toInt().coerceIn(-32768, 32767)
                out[idx] = (v and 0xFF).toByte()
                out[idx + 1] = ((v shr 8) and 0xFF).toByte()
            }
        }
    }

    /** 读取 16bit 小端样本（越界返回 0，避免尾部插值越界）。 */
    private fun readS16(
        b: ByteArray,
        sampleIndex: Int,
        count: Int,
    ): Int {
        if (sampleIndex < 0 || sampleIndex >= count) return 0
        val i = sampleIndex * 2
        return ((b[i].toInt() and 0xFF) or (b[i + 1].toInt() shl 8)).toShort().toInt()
    }

    /** 走与采集循环一致的封包/编码/发送路径（[feedPcm] 与采集循环共用）。 */
    private fun dispatchPcm(pcm: ByteArray) {
        try {
            val payloads: List<ByteArray> =
                when (codec) {
                    AudioCodec.AAC_LC -> {
                        val encoder = aacEncoder ?: return
                        encoder.encode(pcm, pcm.size)
                    }
                    AudioCodec.OPUS -> {
                        val encoder = opusEncoder ?: return
                        encoder.encode(pcm)
                    }
                    else -> listOf(pcm)
                }
            if (payloads.isEmpty()) return
            var sendFailed = false
            for (payload in payloads) {
                if (!sendPayload(payload)) {
                    sendFailed = true
                    break
                }
            }
            if (!sendFailed) sentCount += payloads.size
            if (sendFailed) stop()
            // 每 50 次喂入输出一行诊断（约 4 秒一次）
            if (fedCount % 50 == 0 && fedCount > 0) {
                val avgGap = if (gapCount > 0) gapSum / gapCount else 0L
                val encStats = opusEncoder?.stats() ?: "-"
                log(
                    "AUDIO-DIAG[$streamType]: 喂入 $fedCount 包 / 已发 $sentCount 包 · " +
                        "源间隔 平均 ${avgGap}ms 最大 ${gapMax}ms · 编码器 $encStats",
                )
                gapSum = 0
                gapMax = 0
                gapCount = 0
            }
        } catch (error: Throwable) {
            log("AUDIO: 直通注入异常 - ${error.message}")
            stop()
        }
    }

    /**
     * 停止音频。
     *
     * 收尾路径不止一条（采集线程 finally、会话结束回调、手动断开），会重复调用；
     * 必须幂等——否则「已恢复手机媒体音量」会打两遍，且在用户于会话期间手动改过
     * 音量时，第二次恢复会用旧值把它覆盖回去。
     */
    fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        running.set(false)
        runCatching { record?.stop() }
        runCatching { record?.release() }
        record = null
        aacEncoder?.stop()
        aacEncoder = null
        opusEncoder?.stop()
        opusEncoder = null
        runCatching { socket?.close() }
        socket = null
        runCatching { syncSocket?.close() }
        syncSocket = null
        syncThread = null
        thread = null
        passthroughBuffer.reset()
        redHistory?.clear()
        redLogged = false
        restorePhoneMedia()
        log("AUDIO: 已停止（共发 $packetsSent 包 / ${bytesSent / 1024}KB）")
    }

    /** 捕获配置：媒体/游戏/导航等常见播放用途（逐项添加，个别用途被系统拒绝时跳过）。 */
    private fun buildCaptureConfig(projection: MediaProjection): AudioPlaybackCaptureConfiguration {
        val builder = AudioPlaybackCaptureConfiguration.Builder(projection)
        listOf(
            AudioAttributes.USAGE_MEDIA,
            AudioAttributes.USAGE_GAME,
            AudioAttributes.USAGE_UNKNOWN,
            AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE,
            AudioAttributes.USAGE_ASSISTANCE_SONIFICATION,
        ).forEach { usage ->
            runCatching { builder.addMatchingUsage(usage) }
        }
        return builder.build()
    }

    // ---- 音频同步包（RTP 时间戳 ↔ NTP，每秒一个） ----

    /**
     * 打开同步包套接字（连接式 UDP：连上之后才能收到 ICMP 端口不可达）。
     * 失败返回 null（例如车机下发的 controlPort 非法）。
     */
    private fun openSyncSocket(): DatagramSocket? =
        try {
            DatagramSocket().apply { connect(InetAddress.getByName(host), controlPort) }
        } catch (error: Exception) {
            log("AUDIO: 同步包套接字创建失败 - ${error.message}")
            null
        }

    /**
     * 发一个同步包。**失败不再终止同步线程**。
     *
     * 2026-10-26 实证（奥迪 MIB3）：音频 SETUP 后 1 秒，第一个同步包引发
     * `ECONNREFUSED`（车机此刻还没在 controlPort 上监听），旧实现 `catch` 后直接
     * 退出线程 —— 整场会话再没有任何同步包，RTP↔NTP 锚点永久缺失。
     * 连接式 UDP 的 ICMP 错误是一次性的，继续发往往就能成功，所以这里只记数不放弃：
     *  - 前 3 次逐条记，之后每 60 次记一条，避免刷屏；
     *  - 连续失败满 10 次重开套接字（车机侧可能换了监听 socket）。
     */
    @Synchronized
    private fun sendSyncOnce(first: Boolean): Boolean {
        val client = syncSocket ?: return false
        return try {
            val next = timestamp
            val current = (next - samplesPerPacket).coerceAtLeast(0L)
            val ntp = TimingServer.ntpFromNanos(System.nanoTime())
            val packet = AudioSyncPacket.build(syncSequence, current, next, ntp, first)
            client.send(DatagramPacket(packet, packet.size))
            syncSequence = (syncSequence + 1) and 0xFFFF
            if (syncFailures > 0) {
                log("AUDIO: 同步包恢复（连续失败 $syncFailures 次后发出）——锚点重建")
                syncFailures = 0
            }
            true
        } catch (error: Exception) {
            syncFailures++
            if (syncFailures <= 3 || syncFailures % 60 == 0) {
                log(
                    "AUDIO: 同步包发送失败（第 $syncFailures 次）- ${error.message}" +
                        "——继续每秒重试，不放弃锚点",
                )
            }
            if (syncFailures % 10 == 0) {
                runCatching { client.close() }
                syncSocket = openSyncSocket()
            }
            false
        }
    }

    /**
     * 立刻补发一轮同步包，让车机重新对齐 RTP↔NTP。
     *
     * 用在「引擎音频停过一段又恢复」（来电结束、音频路由切回）之后：车机长时间收不到
     * 音频会把锚点判超时，恢复瞬间只按到达时刻猜，容易出现开头几百毫秒被丢/爆音。
     */
    @Synchronized
    fun resyncAnchor(reason: String) {
        if (!running.get() || controlPort <= 0 || syncSocket == null) return
        var sent = 0
        repeat(3) { if (sendSyncOnce(false)) sent++ }
        log("AUDIO: $reason → 补发 $sent 个同步包重新对齐车机时钟")
    }

    private fun startSyncLoop() {
        if (controlPort <= 0) {
            log("AUDIO: 车机未下发 controlPort——不发同步包（只能靠音频包自带时间戳）")
            return
        }
        val client = openSyncSocket() ?: return
        syncSocket = client
        log("AUDIO: 同步包已启用 → $host:$controlPort（每 ${SYNC_INTERVAL_MILLIS}ms 一个，建立 RTP↔NTP 锚点）")
        val worker =
            Thread(
                {
                    // 注意：**不能**在循环条件里判 `client.isClosed`——连续失败时
                    // sendSyncOnce 会把套接字关掉重开，那个判断会让线程直接退出。
                    while (running.get()) {
                        val sent = sendSyncOnce(first = !syncFirstSent)
                        if (sent && !syncFirstSent) {
                            syncFirstSent = true
                            log(
                                "AUDIO: 首个同步包已发出（X 位=1）——" +
                                    "车机若仍无声，说明它没在等这个锚点",
                            )
                        }
                        try {
                            Thread.sleep(SYNC_INTERVAL_MILLIS)
                        } catch (_: InterruptedException) {
                            break
                        }
                    }
                },
                "audio-sync",
            )
        worker.isDaemon = true
        worker.start()
        syncThread = worker
    }

    // ---- 采集循环 → 封包发送 ----

    private fun runCaptureLoop() {
        val recorder = record ?: return
        val frameBytes = framePcmBytes
        val pcm = ByteArray(frameBytes)
        // 诊断抽样节拍：PCM 约 125 包/s（每 20s 一次）；AAC 约 47 帧/s
        val diagnosticStride = if (codec == AudioCodec.AAC_LC) 600 else 1875
        try {
            while (running.get()) {
                val read =
                    try {
                        recorder.read(pcm, 0, pcm.size, AudioRecord.READ_BLOCKING)
                    } catch (error: Exception) {
                        log("AUDIO: 采集读取异常 - ${error.message}")
                        break
                    }
                if (read < 0) {
                    readFailures++
                    if (readFailures == 1 || readFailures % 100 == 0) {
                        log("AUDIO: 采集错误码 $read（连续 $readFailures 次）")
                    }
                    if (readFailures > 500) break
                    Thread.sleep(READ_TIMEOUT_QUIET_MILLIS)
                    continue
                }
                if (read < frameBytes) continue // 残包（缓冲尾部）：丢弃，等下一个整包
                readFailures = 0

                // 内容诊断（2026-10-17）：播放捕获可能拿到全零（app 禁止捕获 / 音频路由
                // 到蓝牙 / 无音频播放）。全零时车机静音但手机仍在响，用户会报「声音在
                // 手机上」——必须能区分「没采到」与「采到了但车机没播」。
                if (sequence < 3 || sequence % diagnosticStride == 0) {
                    var nonZero = 0
                    var peak = 0
                    var index = 0
                    while (index + 1 < read) {
                        val value = ((pcm[index + 1].toInt() and 0xFF) shl 8) or (pcm[index].toInt() and 0xFF)
                        val magnitude = if (value > 32767) 65536 - value else value
                        if (magnitude > peak) peak = magnitude
                        if (magnitude > 0) nonZero++
                        index += 2
                    }
                    val percent = nonZero * 100 / (read / 2).coerceAtLeast(1)
                    log(
                        "AUDIO: 采集样本 seq=$sequence 非零$percent% 峰值=$peak" +
                            if (peak == 0) "（⚠ 全零：捕获不到音频——app 可能禁止播放捕获或音频走了蓝牙）" else "",
                    )
                }

                // PCM 通路：采一段 = 发一包；AAC 通路：喂一帧 PCM，编码器可能吐出 0~N 帧
                val payloads: List<ByteArray> =
                    if (codec == AudioCodec.AAC_LC) {
                        val encoder = aacEncoder ?: break
                        encoder.encode(pcm, frameBytes)
                    } else {
                        listOf(pcm)
                    }
                if (payloads.isEmpty()) continue

                // 一次性自检：把编码结果自己解一遍，证明比特流有效且非静音。
                // 这是「车机无声」最需要的一条证据——如果自解码峰值是 0，问题在编码器，
                // 如果自解码有声音而车机静音，问题在车机侧（会话/音量/路由）。
                if (!selfChecked) {
                    selfChecked = true
                    verifyEncodedFrame(payloads.first())
                }

                var sendFailed = false
                for (payload in payloads) {
                    if (!sendPayload(payload)) {
                        sendFailed = true
                        break
                    }
                }
                if (sendFailed) break
                val now = android.os.SystemClock.uptimeMillis()
                if (now - lastLogAt >= 30_000L) {
                    lastLogAt = now
                    log(
                        "AUDIO: 推流中（累计 $packetsSent 包，${bytesSent / 1024}KB，" +
                            "seq=$sequence ts=$timestamp）",
                    )
                }
            }
        } catch (error: Throwable) {
            // 后台线程未捕获异常会杀死整个进程——音频异常绝不允许冒泡
            log("AUDIO: 采集线程异常退出 - ${error.message}")
        } finally {
            stop()
        }
    }

    /** 封包 + 发送一包（同步更新 seq/ts/nonce 游标）。返回 false 表示链路已断。 */
    private fun sendPayload(payload: ByteArray): Boolean {
        // 发送均匀化（仅直通模式生效）：把「一次到一大块」摊平成按帧时长的稳定节奏。
        // 采集模式（record != null）的音频源本身就是实时的，节流等于白加延迟。
        if (record == null && running.get() && pacingEnabled) {
            val intervalMs = (samplesPerPacket * 1_000L / sampleRate).coerceAtLeast(1L)
            val now = android.os.SystemClock.uptimeMillis()
            if (nextSendAt == 0L) nextSendAt = now
            val wait = nextSendAt - now
            // 单次最多等 40ms：万一上游持续快于实时（缓冲积压），宁可牺牲一点均匀性，
            // 也不能把读线程长时间堵住——那会让 passthroughBuffer 越积越多。
            if (wait > 0L) {
                try {
                    Thread.sleep(wait.coerceAtMost(40L))
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return false
                }
            }
            nextSendAt += intervalMs
            // 落后超过 200ms 就重锚，避免延迟越积越多（断流后恢复的常见场景）
            val after = android.os.SystemClock.uptimeMillis()
            if (after - nextSendAt > 200L) nextSendAt = after
        }
        // RTP 冗余仅在「开启 且 压缩编码」时生效；PCM 大帧会撑爆 MTU，强制走普通路径。
        val useRed = rtpRedundancyLevels > 0 && codec != AudioCodec.PCM && redHistory != null
        val wire =
            if (useRed) {
                sealPacketRedundant(payload, sequence, timestamp, nonceCounter, markerPending, rtpRedundancyLevels)
            } else {
                sealPacket(payload, sequence, timestamp, nonceCounter, markerPending)
            }
        markerPending = false
        val thisTs = timestamp
        sequence = (sequence + 1) and 0xFFFF
        timestamp += samplesPerPacket
        nonceCounter++
        if (useRed) {
            redHistory!!.addLast(RedEntry(payload, thisTs))
            while (redHistory!!.size > rtpRedundancyLevels) redHistory!!.removeFirst()
            if (!redLogged) {
                redLogged = true
                log(
                    "AUDIO: RTP 冗余已启用（级数=$rtpRedundancyLevels，" +
                        "${if (codec == AudioCodec.OPUS) "OPUS" else "AAC-LC"} 通路，每包抗 ${rtpRedundancyLevels} 帧丢包）",
                )
            }
        } else if (rtpRedundancyLevels > 0 && codec == AudioCodec.PCM && !redLogged) {
            redLogged = true
            log("AUDIO: RTP 冗余对 PCM 通路关闭（48k 立体声帧过大，冗余会超 MTU 被分片）——仅 AAC/OPUS 生效")
        }
        val client = socket ?: return false
        return try {
            client.send(DatagramPacket(wire, wire.size))
            packetsSent++
            bytesSent += wire.size
            true
        } catch (error: IOException) {
            log("AUDIO: UDP 发送失败 - ${error.message}")
            false
        }
    }

    /** 用平台解码器把首帧解回 PCM，记录峰值（后台线程，不阻塞推流）。 */
    private fun verifyEncodedFrame(frame: ByteArray) {
        val payload = frame.copyOf()
        Thread(
            {
                try {
                    val result = AacLcEncoder.decodePeak(payload, sampleRate, CHANNELS)
                    if (result == null) {
                        log("AUDIO: 自检 = 本机解码器没解出样本（首帧 ${payload.size}B）——无法证伪，继续推流")
                        return@Thread
                    }
                    val (peak, samples) = result
                    log(
                        "AUDIO: 自检 = 首帧 ${payload.size}B 本机解码 $samples 个样本，峰值 $peak" +
                            if (peak > 0) {
                                "（✓ 比特流有效且非静音：车机若无声，问题不在编码/加密）"
                            } else {
                                "（⚠ 解码为全零：AAC 编码器输出的是静音数据）"
                            },
                    )
                } catch (error: Exception) {
                    log("AUDIO: 自检解码异常 - ${error.message}")
                }
            },
            "audio-selfcheck",
        ).apply {
            isDaemon = true
            start()
        }
    }

    /** 组一个 RTP 包：明文 PCM → ChaCha20 密封 → [头][密文+tag][nonce8]。（internal：测试直测） */
    internal fun sealPacket(
        pcm: ByteArray,
        sequence: Int,
        timestamp: Long,
        nonceCounter: Long,
        marker: Boolean = false,
    ): ByteArray {
        val header = buildRtpHeader(sequence, timestamp, marker)
        // 关键：CarPlay 线格式的 LPCM 是**大端** s16 样本——接收端对收到的负载做
        // byteSwapS16 转回小端再喂 AudioTrack（DiPlay AndroidMediaSink.kt:1687）。
        // Android 采集是原生小端，因此这里必须换序；漏掉会得到「沙沙声」（2026-10-10 实测）。
        //
        // AAC 通路不做换序：AAC 比特流是字节流，不是 16bit 样本序列，换序会直接把
        // 比特流搅烂（车机解码失败 → 有包无声）。
        // 只有裸 PCM 是 16bit 样本序列，需要换序；AAC / OPUS 都是**压缩字节流**，
        // 换序会把比特流搅烂（车机解码失败 → 有包无声）。
        val wirePayload = if (codec == AudioCodec.PCM) bigEndianS16(pcm) else pcm
        return assembleWire(header, wirePayload, nonceCounter)
    }

    /**
     * 组一个带 RFC 2198 冗余的 RTP 包。
     *
     * 结构（明文）：[N×4 字节冗余块头][1 字节主块头][N 个冗余数据块][主数据块]。
     * 每个冗余块头 4 字节：`|F=1|PT(7)|tsOffset(14)|len(10)|`；主块头 1 字节 `|F=0|PT(7)|`。
     * 冗余块头里的 tsOffset = 主包 RTP 时间戳 − 该冗余块时间戳（单位与 RTP 时钟一致，
     * 即音频样本数），len = 该冗余块字节数（≤1023）。
     *
     * 这是 AirPlay 2 实时音频丢包补偿的标准做法（openairplay 接收端明确按 RFC 2198 解析），
     * 车机端据此在丢包时从后续包恢复——与 iPhone 的无线 CarPlay 行为一致。
     *
     * 注意：一旦开了冗余，载荷**必须**按此结构封装（哪怕 0 个冗余块也要有那 1 字节主块头），
     * 否则接收端按冗余格式解析会直接坏掉——所以关闭时走 [sealPacket] 普通路径，不调用这里。
     */
    internal fun sealPacketRedundant(
        pcm: ByteArray,
        sequence: Int,
        timestamp: Long,
        nonceCounter: Long,
        marker: Boolean,
        levels: Int,
    ): ByteArray {
        val header = buildRtpHeader(sequence, timestamp, marker)
        val history = redHistory ?: return sealPacket(pcm, sequence, timestamp, nonceCounter, marker)
        val n = minOf(levels, history.size)
        // 取最近 n 条（最旧..最新）。
        val start = history.size - n
        val headerBytes = ByteArray(n * 4 + 1)
        var p = 0
        for (i in 0 until n) {
            val entry = history[start + i]
            val tsOffset = (timestamp - entry.ts).toInt().coerceIn(0, 0x3FFF)
            val len = entry.payload.size.coerceAtMost(0x3FF)
            // F=1（后面还有主块头），PT=streamType 低 7 位
            headerBytes[p++] = (0x80 or (streamType.toInt() and 0x7F)).toByte()
            headerBytes[p++] = ((tsOffset ushr 6) and 0xFF).toByte()
            headerBytes[p++] = (((tsOffset and 0x3F) shl 2) or ((len ushr 8) and 0x03)).toByte()
            headerBytes[p++] = (len and 0xFF).toByte()
        }
        // 主块头：F=0，PT=streamType 低 7 位
        headerBytes[p] = (streamType.toInt() and 0x7F).toByte()
        // 数据块：先 n 个冗余（按头顺序），再主数据（当前 pcm，已是编码后字节流）
        var dataBytes = pcm.size
        for (i in 0 until n) dataBytes += history[start + i].payload.size
        val structure = ByteArray(dataBytes)
        var q = 0
        for (i in 0 until n) {
            val entry = history[start + i]
            entry.payload.copyInto(structure, q)
            q += entry.payload.size
        }
        pcm.copyInto(structure, q)
        return assembleWire(header, structure, nonceCounter)
    }

    /** [头][ChaCha20密封(负载)+tag][8字节小端nonce] 拼装（加密对冗余结构透明）。 */
    private fun assembleWire(
        header: ByteArray,
        wirePayload: ByteArray,
        nonceCounter: Long,
    ): ByteArray {
        // AAD = header 的 timestamp+ssrc 段（字节 4..12）——catplay "modern AAD" / DiPlay 解密同款
        val aad = header.copyOfRange(4, RTP_HEADER_LEN)
        val sealed = AirPlayCrypto.chachaSeal(streamKey, AirPlayCrypto.nonce64(nonceCounter), wirePayload, aad)
        val wire = ByteArray(RTP_HEADER_LEN + sealed.size + NONCE_SIZE)
        header.copyInto(wire, 0)
        sealed.copyInto(wire, RTP_HEADER_LEN)
        // 尾部 8 字节小端 nonce（与 AirPlayCrypto.nonce64 的低 8 字节一致）
        for (index in 0 until NONCE_SIZE) {
            wire[wire.size - NONCE_SIZE + index] =
                ((nonceCounter ushr (8 * index)) and 0xFF).toByte()
        }
        return wire
    }


    private fun buildRtpHeader(
        sequence: Int,
        timestamp: Long,
        marker: Boolean = false,
    ): ByteArray {
        val header = ByteArray(RTP_HEADER_LEN)
        header[0] = 0x80.toByte() // version=2
        // PT 跟随流类型（catplay `payload_type = stream_type`）：100=PCM 通路、102=AAC 通路
        header[1] = ((if (marker) 0x80 else 0) or (streamType.toInt() and 0x7F)).toByte()
        header[2] = ((sequence ushr 8) and 0xFF).toByte()
        header[3] = (sequence and 0xFF).toByte()
        val ts = timestamp.toInt()
        header[4] = ((ts ushr 24) and 0xFF).toByte()
        header[5] = ((ts ushr 16) and 0xFF).toByte()
        header[6] = ((ts ushr 8) and 0xFF).toByte()
        header[7] = (ts and 0xFF).toByte()
        header[8] = ((ssrc ushr 24) and 0xFF).toByte()
        header[9] = ((ssrc ushr 16) and 0xFF).toByte()
        header[10] = ((ssrc ushr 8) and 0xFF).toByte()
        header[11] = (ssrc and 0xFF).toByte()
        return header
    }
}
