package io.github.shaonialife.carptocar.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 失败快照的行格式：这是唯一能在事后还原「车机最后说了什么」的载体，
 * 格式一旦漂移，历史快照就没法和新快照对比着看。
 */
class SessionTraceTest {
    @Test
    fun csmWithoutBodyHasNoBodyField() {
        val line = SessionTrace.formatCsm("10:00:00.000", SessionTrace.Dir.TX, "bt", 0x1D00, ByteArray(0))
        assertEquals("[10:00:00.000] TX bt csm id=0x1d00 len=0", line)
    }

    @Test
    fun csmCarriesFullHexBody() {
        val body = byteArrayOf(0x01, 0x00, 0x02, 0x00)
        val line = SessionTrace.formatCsm("10:00:00.000", SessionTrace.Dir.RX, "tunnel", 0x1D01, body)
        assertEquals("[10:00:00.000] RX tunnel csm id=0x1d01 len=4 body=01 00 02 00", line)
    }

    @Test
    fun csmKeepsHeadAndTailOfOversizedBody() {
        // 真实场景：0x1D01 能力清单可能上千字节。全记会爆缓冲，只留头又看不出结尾是否完整
        val body = ByteArray(SessionTrace.MAX_BODY_BYTES + 100) { (it % 256).toByte() }
        val line = SessionTrace.formatCsm("10:00:00.000", SessionTrace.Dir.RX, "bt", 0x1D01, body)

        assertTrue(line.contains("省略100字节"))
        assertTrue(line.contains("len=${body.size}"))
        // 头尾都要在：尾部字节是「报文是否被截断」的判据
        assertTrue(line.contains("body=00 01 02"))
        val tail = body.copyOfRange(body.size - 4, body.size).joinToString(" ") { "%02x".format(it) }
        assertTrue(line.contains(tail))
    }

    @Test
    fun csmNoteIsAppended() {
        val line =
            SessionTrace.formatCsm("10:00:00.000", SessionTrace.Dir.TX, "tunnel", 0x5001, byteArrayOf(0x01), "raw")
        assertTrue(line.endsWith("(raw)"))
    }

    @Test
    fun rtspKeepsFirstLineAndIndentsRest() {
        val text = "POST /command RTSP/1.0\r\nCSeq: 7\r\nContent-Length: 0\r\n\r\n"
        val line = SessionTrace.formatRtsp("10:00:00.000", SessionTrace.Dir.TX, "airplay-event", text)

        assertTrue(line.startsWith("[10:00:00.000] TX airplay-event rtsp len="))
        // 首行进摘要，便于按行扫读
        assertTrue(line.contains("POST /command RTSP/1.0"))
        // 其余行加缩进，避免和报文行混在一起
        assertTrue(line.contains("    | CSeq: 7"))
    }

    @Test
    fun binarySummarisesLargePayload() {
        val body = ByteArray(1000) { 0x41 }
        val text = SessionTrace.binary(body, limit = 16)

        assertTrue(text.contains("<binary 1000B>"))
        assertTrue(text.contains("+984B"))
        // 只留前 limit 字节：1000B 的 plist 全 hex 会把一行撑爆
        assertTrue(text.count { it == ' ' } < 30)
    }

    @Test
    fun binaryIgnoresEmptyPayload() {
        assertEquals("", SessionTrace.binary(ByteArray(0)))
    }
}
