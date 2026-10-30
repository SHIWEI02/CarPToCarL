package io.github.shaonialife.carptocar.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat

/**
 * PCM → OPUS 编码器（type=100 alert/导航提示通路用）。
 *
 * 为什么需要它（2026-10-29 MacPlay 实测）：车机 `/info` 的 alert 档
 * `output=0x40008800` 最高位是 **OPUS 48k 单声道**（bit30），media 档没有 OPUS 位——
 * alert（导航提示）的原生编码就是 OPUS 语音。此前往 alert 流发 PCM 时：
 *  - Audi 直接 SETUP 400 并拆链
 *  - MacPlay 虽然收下（SETUP 200），但**4080 包只有 406 包能解码**，
 *    听感是「卡顿杂音、几乎无法分辨」——PCM 被当 OPUS 解码的典型症状。
 *
 * 线格式：RTP 负载 = **裸 OPUS 包**（一包一帧，固定 20ms）。RTP 时间戳按 **960 样本
 * @48kHz 时钟**步进（RFC 7587，与编码器内部率解耦）；编码器内部率可配 16k/48k——
 * 16k 即「原生 16k 编码」，直接吃 16k 导航 TTS，省掉上采样（见 [OpusEncoder]）。
 * Android `MediaCodec` 的 `audio/opus` 编码器每帧输出一个裸 OPUS 包，可直接用。
 * 注意首帧可能带 CSD 头（identification header），按输出 buffer 的 flags 跳过。
 */
class OpusEncoder(
    private val sampleRate: Int = 48_000,
    private val bitRate: Int = 24_000,
    private val log: (String) -> Unit,
) {
    companion object {
        /** OPUS 一帧固定 20ms = 960 样本（@48kHz）。同时也是 RTP 时钟帧：
         * 无论编码内部率是 16k 还是 48k，RTP 时间戳都按 960 样本/帧步进（RFC 7587）。 */
        const val FRAME_SAMPLES = 960

        /** RTP 时钟恒为 48kHz（RFC 7587）：OPUS 的 RTP 时间戳与 spf 一律按此率计算，
         * 与编码内部率（[OpusEncoder] 的 sampleRate）解耦——这是「原生 16k 编码、
         * 省掉 16k→48k 上采样」仍能正确对接车机的根因。 */
        const val RTP_CLOCK_RATE = 48_000

        /** 一帧 PCM 的字节数（16bit 单声道），按给定采样率算：
         *  16k → 640B（320 样本）、48k → 1920B（960 样本）。 */
        fun pcmBytes(rate: Int = RTP_CLOCK_RATE): Int = rate * 20 / 1000 * 2

        private const val MIME = "audio/opus"
        private const val TIMEOUT_US = 10_000L

        /**
         * 本机是否存在可用的 OPUS **编码器**（不是解码器）。
         *
         * 用途：SETUP 协商前先探一次。若先协商成 OPUS 再发现编码器起不来，
         * 接收端已经把这条流按 OPUS 开好了，只能整条放弃——不如协商阶段就选 PCM。
         *
         * 设备实测（2026-10-29 小米 / Android 17）：`c2.android.opus.encoder`
         * 存在，sdk 支持 8/12/16/24/48k、1~2 声道。
         */
        /**
         * 端到端自检：创建编码器 → 喂若干帧静音 → 看能否吐出 OPUS 包 → 立刻释放。
         *
         * 返回可直接打进日志的一行诊断。`isSupported()` 只能证明「编码器列表里有它」，
         * 不能证明它能真正编码（Codec2 组件缺 lib 或权限时照样能 createByType，
         * 但 configure/start 会失败）。这里全程试一遍，代价只有几十毫秒。
         */
        fun probe(): String =
            try {
                val enc = OpusEncoder(48_000, 24_000) { }
                if (!enc.start()) {
                    "编码器创建失败"
                } else {
                    var packets = 0
                    var maxBytes = 0
                    // 静音 4 帧（960 样本 = 1920B/帧），Opus 有 lookahead，前几帧可能不出包
                    repeat(6) {
                        val out = enc.encode(ByteArray(pcmBytes()))
                        packets += out.size
                        out.forEach { if (it.size > maxBytes) maxBytes = it.size }
                    }
                    enc.stop()
                    if (packets == 0) "编码器可启动但 6 帧静音未产出任何包" else "可用（6 帧静音出 $packets 包，最大 $maxBytes B）"
                }
            } catch (error: Throwable) {
                "自检异常：${error.message}"
            }

        fun isSupported(): Boolean =
            try {
                android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS)
                    .codecInfos
                    .any { info ->
                        info.isEncoder &&
                            info.supportedTypes.any { type -> type.equals(MIME, ignoreCase = true) }
                    }
            } catch (_: Throwable) {
                false
            }
    }

    private var codec: MediaCodec? = null

    /** 本编码器内部帧样本数（20ms @ sampleRate）：16k → 320，48k → 960。 */
    private val inputFrameSamples = sampleRate * 20 / 1000

    /** 本编码器每帧输入的 PCM 字节数（16bit 单声道）。 */
    private val inputPcmBytes = inputFrameSamples * 2

    /**
     * 待喂给编码器的 PCM 帧。MediaCodec 的输入 buffer 可能一时拿不到
     * （编码器还在处理上一批），拿不到时数据留在这里，绝不丢弃。
     */
    private val inputQueue = ArrayDeque<ByteArray>()
    private var inputOffset = 0

    /** 已喂入的帧数 / 已产出的包数（诊断丢帧用，见 [stats]）。 */
    private var fedFrames = 0
    private var outFrames = 0

    /** 一行诊断：喂入 N 帧 → 产出 M 包，欠 M-N 表示编码器内部还有积压。 */
    fun stats(): String = "喂入 $fedFrames 帧 / 产出 $outFrames 包 / 队列余 ${inputQueue.size} 帧"

    private var inputDone = false
    private var outputDone = false
    /** 每次 encode() 取回的输出帧（裸 OPUS 包）。 */
    private val pendingFrames = ArrayDeque<ByteArray>()

    /** 创建并启动编码器。失败返回 false（调用方回落 PCM）。 */
    fun start(): Boolean {
        return try {
            val format =
                MediaFormat.createAudioFormat(MIME, sampleRate, 1).apply {
                    setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, inputPcmBytes * 4)
                    // OPUS 的复杂度 0~10，5 是速度/质量平衡点（语音够用）
                    setInteger(MediaFormat.KEY_COMPLEXITY, 5)
                }
            codec = MediaCodec.createEncoderByType(MIME).apply {
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }
            inputDone = false
            outputDone = false
            pendingFrames.clear()
            log("OPUS: 编码器已启动（${sampleRate}Hz mono ${bitRate}bps，一帧 ${inputFrameSamples} 样本 / ${inputPcmBytes}B）")
            true
        } catch (error: Exception) {
            log("OPUS: 编码器启动失败 - ${error.message}")
            codec = null
            false
        }
    }

    /**
     * 喂入一帧 PCM（单声道 s16le，恰好 [FRAME_SAMPLES] 个样本），
     * 取回 0..n 个裸 OPUS 包。
     */
    fun encode(pcmFrame: ByteArray): List<ByteArray> {
        val c = codec ?: return emptyList()
        if (outputDone) return emptyList()
        pendingFrames.clear()
        if (pcmFrame.isNotEmpty()) {
            inputQueue.addLast(pcmFrame)
            fedFrames++
        }
        try {
            // 喂输入
            //
            // 2026-10-29 修：原实现是「dequeueInputBuffer 拿不到就 break」，
            // 拿到一半就退出的情况**直接把剩余 PCM 丢掉**，且无补偿——
            // 一帧 20ms，丢一帧就是 20ms 空档，听感正是「卡顿」。
            // 现在未喂完的数据留在 inputQueue 里，下次 encode 接着喂，一字节不丢。
            if (!inputDone) {
                var guard = 0
                while (inputQueue.isNotEmpty() && guard++ < 8) {
                    val inIndex = c.dequeueInputBuffer(0L)
                    if (inIndex < 0) break // 编码器忙，留着下次
                    val buf = c.getInputBuffer(inIndex) ?: break
                    buf.clear()
                    val head = inputQueue.first()
                    val remain = head.size - inputOffset
                    if (remain <= 0) {
                        inputQueue.removeFirst()
                        inputOffset = 0
                        c.queueInputBuffer(inIndex, 0, 0, 0L, 0)
                        continue
                    }
                    val n = minOf(remain, buf.remaining())
                    if (n <= 0) break
                    buf.put(head, inputOffset, n)
                    // Opus 编码器内部按 frame duration 切帧，分块喂是安全的
                    c.queueInputBuffer(inIndex, 0, n, 0L, 0)
                    inputOffset += n
                    if (inputOffset >= head.size) {
                        inputQueue.removeFirst()
                        inputOffset = 0
                    }
                }
            }
            // 取输出
            val info = MediaCodec.BufferInfo()
            while (true) {
                val outIndex = c.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> break
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> continue
                    outIndex >= 0 -> {
                        val buf = c.getOutputBuffer(outIndex)
                        if (buf != null && info.size > 0) {
                            // 跳过 CSD/配置帧（flags 含 BUFFER_FLAG_CODEC_CONFIG）
                            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                val frame = ByteArray(info.size)
                                buf.position(info.offset)
                                buf.get(frame)
                                pendingFrames.addLast(frame)
                                outFrames++
                            }
                        }
                        c.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputDone = true
                            break
                        }
                    }
                    else -> break
                }
            }
        } catch (error: Exception) {
            log("OPUS: 编码异常 - ${error.message}")
        }
        return pendingFrames.toList()
    }

    fun stop() {
        try {
            codec?.stop()
            codec?.release()
        } catch (_: Exception) {
        }
        codec = null
        pendingFrames.clear()
        inputQueue.clear()
        inputOffset = 0
    }
}
