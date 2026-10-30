package io.github.shaonialife.carptocar.iap2

/**
 * iAP2「正在播放」信息（0x5001 NowPlayingUpdate）的载荷与编码。
 *
 * 字段布局来自 iAP2 NowPlaying 规范（MFi Accessory Interface Specification；
 * 亦与开源实现 iap2-rs 的 NowPlayingParser 解码字段一一对应）：
 *
 *   顶层 param 0x0000 = media item 组：
 *     0x0001 title(string) · 0x0004 duration(u32 ms) · 0x000C artist(string) ·
 *     0x001A album(string)
 *   顶层 param 0x0001 = playback 组：
 *     0x0000 status(u8: 0 停止/1 播放/2 暂停) · 0x0001 elapsed(u32 ms) ·
 *     0x0005 shuffle(u8) · 0x0006 repeat(u8) · 0x0007 appName(string)
 *
 * 数据源可以是 CarLife 内置播放器经 0x10035 推来的媒体信息，或系统媒体会话
 * （MediaSessionsState）——由各链路的 provider 决定。
 */
data class CarPlayNowPlaying(
    val title: String?,
    val artist: String?,
    val album: String?,
    val durationMs: Int,
    val status: Int,
    val elapsedMs: Int,
    val shuffleMode: Int = 0,
    val repeatMode: Int = 0,
    val appName: String?,
)

object Iap2NowPlaying {
    /** 播放状态枚举（与 iap2-rs PlaybackStatus 一致）。 */
    const val STATUS_STOPPED = 0
    const val STATUS_PLAYING = 1
    const val STATUS_PAUSED = 2

    /** 车机下发的播放控制（0x5002）。null = 该字段没带。 */
    data class PlaybackCommand(
        val status: Int?,
        val elapsedMs: Int?,
        val shuffleMode: Int? = null,
        val repeatMode: Int? = null,
    )

    /**
     * 车机下发播放控制的落点（由 SessionController 注册）。
     *
     * 目前只用于「车机拖动进度条 → 手机播放器 seek」：CarPlay 车机上拖动进度条时，
     * 车机并不会去改自己的播放点，而是发 0x5002 要求手机跳转——手机不响应时
     * 表现为「进度条能拖、松手又弹回原处」。
     */
    @Volatile
    var playbackCommandSink: ((PlaybackCommand) -> Unit)? = null

    /**
     * 解析 0x5002 参数体。
     *
     * 结构与 [body] 的 playback 组对称（这是从「车机发送清单里同时声明 0x5000/0x5001/
     * 0x5002」反推的假设，尚缺真机报文）：
     *   顶层 param 0x0001 → 0x0000 status(u8) / 0x0001 elapsed(u32) / 0x0005 / 0x0006
     *
     * 为降低猜错代价，同时容忍两种退化形态：字段直接挂在顶层；或顶层只给一个 4 字节
     * 裸 elapsed。解析不出任何字段返回 null，调用方据此只记日志不动播放器。
     */
    fun parsePlaybackCommand(body: ByteArray): PlaybackCommand? {
        val top = Iap2Csm.parseParams(body)
        if (top.isEmpty()) return null
        val group = top.firstOrNull { it.id == 0x0001 && isParamGroup(it.value) }
        val fields = if (group != null) Iap2Csm.parseParams(group.value) else top
        var status: Int? = null
        var elapsed: Int? = null
        var shuffle: Int? = null
        var repeat: Int? = null
        for (p in fields) {
            when (p.id) {
                0x0000 -> if (p.value.size == 1) status = p.value[0].toInt() and 0xFF
                0x0001 -> if (p.value.size == 4) elapsed = readU32(p.value)
                0x0005 -> if (p.value.size == 1) shuffle = p.value[0].toInt() and 0xFF
                0x0006 -> if (p.value.size == 1) repeat = p.value[0].toInt() and 0xFF
            }
        }
        if (status == null && elapsed == null) return null
        return PlaybackCommand(status, elapsed, shuffle, repeat)
    }

    /**
     * 该字节串本身是否是一组完整参数。
     *
     * 判据必须是「按 TLV 解析后每个参数都恰好铺满整个字节串」——**不能**用
     * 「首 u16 == 自身长度」：子参数组的首 u16 是它**第一个子参数**的长度
     * （例如 status 参数的 4+1=5），与整组长度无关。这个错误曾让 0x5002 全部解析失败。
     */
    private fun isParamGroup(value: ByteArray): Boolean {
        if (value.size < 4) return false
        val params = Iap2Csm.parseParams(value)
        if (params.isEmpty()) return false
        var consumed = 0
        for (p in params) consumed += 4 + p.value.size
        return consumed == value.size
    }

    private fun readU32(value: ByteArray): Int =
        ((value[0].toInt() and 0xFF) shl 24) or
            ((value[1].toInt() and 0xFF) shl 16) or
            ((value[2].toInt() and 0xFF) shl 8) or
            (value[3].toInt() and 0xFF)

    /** 构造一条 0x5002 参数体（供本地模拟拖动使用，与解析器对称）。 */
    fun buildPlaybackCommand(
        status: Int?,
        elapsedMs: Int?,
    ): ByteArray {
        var inner = ByteArray(0)
        if (status != null) inner += Iap2Csm.u8(0x0000, status)
        if (elapsedMs != null) inner += Iap2Csm.u32(0x0001, elapsedMs.toLong())
        return Iap2Csm.param(0x0001, inner)
    }

    /**
     * 构造 0x5001 NowPlayingUpdate 完整帧（带真实歌名/歌手/专辑/播放状态）。
     * 空字符串字段仍给一个空串 param，保持结构与空态常量同构。
     */
    fun encode(np: CarPlayNowPlaying): ByteArray = Iap2Csm.encode(Iap2MessageIds.NOW_PLAYING_UPDATE, body(np))

    /**
     * 只返回**参数体**（不含 `0x4040 | len | msgId` 帧头）。
     *
     * 走 `send(...)/sendFireAndForget(...)` 这类「按消息 ID 发体」的链路时必须用它：
     * 那些方法会自己补帧头，把 [encode] 的完整帧当体再封一次，对端解析出来就是
     * `0x4040 0x5001 ...` 这种非法参数组。WinPlay 1.2.4 实测：收到双重封包的 0x5001
     * 后其「接收进程异常」直接退出 → 蓝牙桥接被拆 → 车机不停重连（2026-10-25）。
     */
    fun body(np: CarPlayNowPlaying): ByteArray {
        val mediaItem =
            Iap2Csm.string(0x0001, np.title.orEmpty()) +
                Iap2Csm.u32(0x0004, np.durationMs.toLong()) +
                Iap2Csm.string(0x000C, np.artist.orEmpty()) +
                Iap2Csm.string(0x001A, np.album.orEmpty())
        val playback =
            Iap2Csm.u8(0x0000, np.status) +
                Iap2Csm.u32(0x0001, np.elapsedMs.toLong()) +
                Iap2Csm.u8(0x0005, np.shuffleMode) +
                Iap2Csm.u8(0x0006, np.repeatMode) +
                Iap2Csm.string(0x0007, np.appName.orEmpty())
        return Iap2Csm.param(0x0000, mediaItem) + Iap2Csm.param(0x0001, playback)
    }
}
