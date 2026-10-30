package io.github.shaonialife.carptocar.iap2

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 车机下发播放控制（0x5002 SetNowPlayingInformation）的解析。
 *
 * 这条消息决定「车机上拖进度条，手机是否跟着跳」。0x5002 的报文体在 Apple 公开表里
 * 查不到，结构按 0x5001 的 playback 组对称假设，所以这里用「构造器 → 解析器」往返
 * 把格式钉住，并用几个退化形态验证容错。
 */
class Iap2NowPlayingCommandTest {
    @Test
    fun `往返：同时带状态与进度`() {
        val body = Iap2NowPlaying.buildPlaybackCommand(
            Iap2NowPlaying.STATUS_PLAYING,
            60_000,
        )
        val cmd = Iap2NowPlaying.parsePlaybackCommand(body)
        assertEquals(Iap2NowPlaying.STATUS_PLAYING, cmd?.status)
        assertEquals(60_000, cmd?.elapsedMs)
    }

    @Test
    fun `往返：只带进度（拖动，不改播放态）`() {
        val cmd = Iap2NowPlaying.parsePlaybackCommand(
            Iap2NowPlaying.buildPlaybackCommand(null, 1_234),
        )
        assertNull(cmd?.status)
        assertEquals(1_234, cmd?.elapsedMs)
    }

    @Test
    fun `往返：只带状态（暂停键）`() {
        val cmd = Iap2NowPlaying.parsePlaybackCommand(
            Iap2NowPlaying.buildPlaybackCommand(Iap2NowPlaying.STATUS_PAUSED, null),
        )
        assertEquals(Iap2NowPlaying.STATUS_PAUSED, cmd?.status)
        assertNull(cmd?.elapsedMs)
    }

    @Test
    fun `空体与无效体返回 null`() {
        assertNull(Iap2NowPlaying.parsePlaybackCommand(ByteArray(0)))
        // 4 字节裸值：不是参数组，也不构成 TLV
        assertNull(Iap2NowPlaying.parsePlaybackCommand(byteArrayOf(0, 0, 0, 1)))
    }

    @Test
    fun `退化形态：字段直接挂在顶层`() {
        // 顶层直接给 0x0001 = u32(33_000)，没有 0x0001 组包装
        val body = Iap2Csm.u32(0x0001, 33_000)
        val cmd = Iap2NowPlaying.parsePlaybackCommand(body)
        assertEquals(33_000, cmd?.elapsedMs)
    }

    @Test
    fun `组判定不能误把首个子参数长度当成组长度`() {
        // 这是曾经的 bug：参数组首 u16 是第一个子参数的长度(5)，不是组总长。
        // 用真实往返报文验证组被正确识别——否则 elapsed 永远解析不到。
        val body = Iap2NowPlaying.buildPlaybackCommand(Iap2NowPlaying.STATUS_PLAYING, 90_000)
        assertEquals("组内应被识别", 90_000, Iap2NowPlaying.parsePlaybackCommand(body)?.elapsedMs)
    }

    // ---- 0x5003：真正的拖动消息（依据 WinPlay 源码，非推断）----

    @Test
    fun `0x5003 按 param0 u32 解析出拖动位置`() {
        val cmd = Iap2NowPlaying.parsePlaybackCommand(Iap2NowPlaying.buildSeekBody(75_000))
        assertEquals(75_000, cmd?.elapsedMs)
        assertNull("拖动不应带播放状态", cmd?.status)
    }

    @Test
    fun `0x5003 报文与 WinPlay 逐字节一致`() {
        // WinPlay iap.ts:87  seek(ms){ send(0x5003,[param(0,u32(ms))]) }
        // param(0, u32(75000)) = 长度 8 | id 0 | 00 01 24 F8
        val expected =
            byteArrayOf(
                0x00, 0x08, 0x00, 0x00,
                0x00, 0x01, 0x24, 0xF8.toByte(),
            )
        assertArrayEquals(expected, Iap2NowPlaying.buildSeekBody(75_000))
    }

    @Test
    fun `0x5003 的 param0 是四字节、不能误当状态`() {
        // 0x5001 组里 param0 是 1 字节 status；0x5003 里 param0 是 4 字节进度。
        // 解析器按长度区分，两者都要对。
        val seek = Iap2NowPlaying.parsePlaybackCommand(Iap2NowPlaying.buildSeekBody(1_000))
        assertEquals(1_000, seek?.elapsedMs)
        assertNull(seek?.status)

        val statusOnly =
            Iap2NowPlaying.parsePlaybackCommand(
                Iap2NowPlaying.buildPlaybackCommand(Iap2NowPlaying.STATUS_PAUSED, null),
            )
        assertEquals(Iap2NowPlaying.STATUS_PAUSED, statusOnly?.status)
        assertNull(statusOnly?.elapsedMs)
    }
}
