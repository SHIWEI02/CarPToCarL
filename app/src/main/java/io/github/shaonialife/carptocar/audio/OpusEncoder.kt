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
 * 线格式：RTP 负载 = **裸 OPUS 包**（一包一帧，20ms = 960 样本 @48kHz）。
 * Android `MediaCodec` 的 `audio/opus` 编码器每帧输出一个裸 OPUS 包，可直接用。
 * 注意首帧可能带 CSD 头（identification header），按输出 buffer 的 flags 跳过。
 */
class OpusEncoder(
    private val sampleRate: Int = 48_000,
    private val bitRate: Int = 24_000,
    private val log: (String) -> Unit,
) {
    companion object {
        /** OPUS 一帧固定 20ms = 960 样本（@48kHz）。 */
        const val FRAME_SAMPLES = 960

        /** 一帧 PCM 的字节数（16bit 单声道）。 */
        fun pcmBytes(): Int = FRAME_SAMPLES * 2

        private const val MIME = "audio/opus"
        private const val TIMEOUT_US = 10_000L
    }

    private var codec: MediaCodec? = null
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
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, pcmBytes() * 4)
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
            log("OPUS: 编码器已启动（${sampleRate}Hz mono ${bitRate}bps，一帧 ${FRAME_SAMPLES} 样本）")
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
        try {
            // 喂输入
            if (!inputDone && pcmFrame.isNotEmpty()) {
                val inIndex = c.dequeueInputBuffer(TIMEOUT_US)
                if (inIndex >= 0) {
                    val buf = c.getInputBuffer(inIndex) ?: return emptyList()
                    buf.clear()
                    val n = minOf(pcmFrame.size, buf.remaining())
                    buf.put(pcmFrame, 0, n)
                    c.queueInputBuffer(inIndex, 0, n, 0L, 0)
                    if (n < pcmFrame.size) inputDone = true
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
    }
}
