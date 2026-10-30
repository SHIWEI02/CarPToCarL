package io.github.shaonialife.carptocar.carlife

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CarLife 媒体信息（0x10035）与进度（0x10036）解析。
 *
 * 两条都是 2026-10-30 按真机报文修正的：
 * - 0x10035 推的是 `CarlifeMediaInfo` **本体**（首 tag 0x0a），无 List 包装；
 * - field 3 是「歌名-歌手」合并串（旧代码当歌手）、field 2 是歌词行（旧当歌名）、
 *   field 6 时长单位是**秒**；
 * - 0x10036 是 protobuf `[0x08][varint 毫秒]`，旧代码按 u32 大端读把 tag 当数据高位。
 *
 * 这里用手工拼的 protobuf 载荷覆盖解析逻辑，重点是多歌手写法不能丢。
 */
class CarLifeMediaInfoTest {
    // ---- 组包工具（只覆盖本协议用到的 3 种 wire type）----

    private fun varint(value: Int): ByteArray {
        var rest = value
        val out = ArrayList<Byte>()
        while (true) {
            val low = rest and 0x7F
            rest = rest ushr 7
            if (rest == 0) {
                out.add(low.toByte())
                return out.toByteArray()
            }
            out.add((low or 0x80).toByte())
        }
    }

    private fun str(field: Int, value: String): ByteArray {
        val body = value.toByteArray(Charsets.UTF_8)
        return byteArrayOf(((field shl 3) or 2).toByte()) + varint(body.size) + body
    }

    private fun bytes(field: Int, value: ByteArray): ByteArray =
        byteArrayOf(((field shl 3) or 2).toByte()) + varint(value.size) + value

    private fun num(field: Int, value: Int): ByteArray =
        byteArrayOf((field shl 3).toByte()) + varint(value)

    /** 拼一条格式 B（无 List 包装）的 CarlifeMediaInfo。 */
    private fun mediaInfo(
        source: String,
        lyric: String,
        titleArtist: String,
        album: String,
        art: ByteArray?,
        durationSec: Int,
    ): ByteArray {
        var payload = str(1, source) + str(2, lyric) + str(3, titleArtist) + str(4, album)
        if (art != null) payload += bytes(5, art)
        payload += num(6, durationSec) + num(7, 0) + str(8, "241840") + num(9, 0)
        return payload
    }

    private fun parse(payload: ByteArray): CarLifeMediaInfo {
        val list = CarLifeProtocol.parseMediaInfoList(payload)
        assertEquals("应解析出 1 条媒体信息", 1, list.size)
        return list[0]
    }

    // ---- 多歌手 ----

    @Test
    fun `英文多歌手按斜杠整串保留`() {
        // 真机同类样例：QQ音乐把「歌名-歌手A/歌手B」塞进 field 3
        val info = parse(mediaInfo("QQ音乐", "And I was like baby", "Baby-Ludacris/Justin Bieber", "My World 2.0", null, 213))
        assertEquals("Baby", info.song)
        assertEquals("Ludacris/Justin Bieber", info.artist)
    }

    @Test
    fun `中文多歌手按斜杠整串保留`() {
        // 本机实测原样：song=你最珍贵 artist=高慧君/张学友
        val info = parse(mediaInfo("QQ音乐", "你最珍贵 - 高慧君/张学友", "你最珍贵-高慧君/张学友", "点播女人心", null, 203))
        assertEquals("你最珍贵", info.song)
        assertEquals("高慧君/张学友", info.artist)
    }

    @Test
    fun `歌手名自带连字符时按第一个连字符拆`() {
        // A-Lin 这类歌手名含 '-'：只有「按第一个 '-' 拆」才正确
        val info = parse(mediaInfo("QQ音乐", "歌词", "给我一个理由忘记-A-Lin", "罪恶感", null, 293))
        assertEquals("给我一个理由忘记", info.song)
        assertEquals("A-Lin", info.artist)
    }

    @Test
    fun `歌名含连字符时会误拆——已知限制`() {
        // 记录的当前行为：按第一个 '-' 拆，歌名里的 '-' 会被截断。
        // 之所以不改成「按最后一个 '-' 拆」，是因为那会把 A-Lin 拆成 artist=Lin，
        // 两种错误互斥，本协议没有可靠判据；实际曲库里歌名带 '-' 远少于歌手名带 '-'。
        val info = parse(mediaInfo("QQ音乐", "歌词", "别-离-某某", "专辑", null, 200))
        assertEquals("别", info.song)
        assertEquals("离-某某", info.artist)
    }

    @Test
    fun `没有连字符时整串当歌名`() {
        val info = parse(mediaInfo("QQ音乐", "歌词", "纯歌手串", "专辑", null, 200))
        assertEquals("纯歌手串", info.song)
        assertEquals("", info.artist)
    }

    // ---- 其余字段语义 ----

    @Test
    fun `时长单位是秒、歌词在 field 2、封面是原始 JPEG`() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2, 3, 4)
        val info = parse(mediaInfo("QQ音乐", "何以你今天竟想找寻伴侣", "人来人往-陈奕迅", "The Line-Up", jpeg, 234))
        assertEquals(234, info.duration) // 秒，不是毫秒
        assertEquals("何以你今天竟想找寻伴侣", info.lyric)
        assertEquals("The Line-Up", info.album)
        assertEquals(8, info.albumArt?.size)
        assertEquals(0xFF.toByte(), info.albumArt?.get(0)) // JPEG 魔数
        assertEquals("QQ音乐", info.source)
    }

    // ---- 进度（0x10036）----

    @Test
    fun `进度按 protobuf varint 解码而不是大端 u32`() {
        // 真机原样：08 AE CE 09 → 157486ms。旧实现按 u32 大端读得 145673225（≈41 小时）。
        val payload = byteArrayOf(0x08) + varint(157_486)
        assertEquals(157_486, CarLifeProtocol.parseMediaProgress(payload))
    }

    @Test
    fun `进度覆盖单字节、双字节、三字节 varint`() {
        assertEquals(0, CarLifeProtocol.parseMediaProgress(byteArrayOf(0x08) + varint(0)))
        assertEquals(1_000, CarLifeProtocol.parseMediaProgress(byteArrayOf(0x08) + varint(1_000)))
        assertEquals(300_000, CarLifeProtocol.parseMediaProgress(byteArrayOf(0x08) + varint(300_000)))
        assertTrue("三字节 varint 应占用 3 字节", varint(300_000).size == 3)
    }
}
