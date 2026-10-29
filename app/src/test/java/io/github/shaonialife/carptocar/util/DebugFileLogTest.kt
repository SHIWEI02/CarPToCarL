package io.github.shaonialife.carptocar.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 日志「本次启动」切片：导出默认只给当前会话，历史轮次不能混进来。
 * 边界来自真实场景——旧版本文件没有标记、标记多次出现、文件刚被 2MB 截断。
 */
class DebugFileLogTest {
    @Test
    fun keepsOnlyContentAfterLastMarker() {
        val text =
            "old line 1\n" +
                "old line 2\n" +
                "[12:00:00.000] ${DebugFileLog.SESSION_MARKER}\n" +
                "previous run line\n" +
                "[13:00:00.000] ${DebugFileLog.SESSION_MARKER}\n" +
                "current line A\ncurrent line B\n"

        val slice = DebugFileLog.currentSessionSlice(text)

        assertTrue(slice.contains("current line A"))
        assertTrue(slice.contains("current line B"))
        // 上一轮与更早的内容都不能出现（标记本身也不该留在正文里）
        assertTrue(!slice.contains("previous run line"))
        assertTrue(!slice.contains("old line 1"))
        assertTrue(!slice.contains(DebugFileLog.SESSION_MARKER))
    }

    @Test
    fun fallsBackToWholeTextWithoutMarker() {
        val text = "line 1\nline 2\n"
        assertEquals(text, DebugFileLog.currentSessionSlice(text))
    }

    @Test
    fun emptyFileStaysEmpty() {
        assertEquals("", DebugFileLog.currentSessionSlice(""))
    }

    @Test
    fun markerAsLastLineYieldsEmptySlice() {
        // 刚写标记、还没有其它日志：切片为空，导出层会退回界面缓冲
        val text = "[13:00:00.000] ${DebugFileLog.SESSION_MARKER}\n"
        assertEquals("\n", DebugFileLog.currentSessionSlice(text))
    }

    @Test
    fun legacyMarkerStillSlices() {
        // 设备上可能存着改名前写下的日志文件（标记是旧名）。只认新名会让它退化成
        // 「无标记 → 返回全文」，历史轮次的噪音全混进导出。
        val text =
            "old line\n" +
                "[12:00:00.000] ${DebugFileLog.LEGACY_SESSION_MARKER}\n" +
                "legacy session line\n"
        val slice = DebugFileLog.currentSessionSlice(text)
        assertTrue(slice.contains("legacy session line"))
        assertTrue(!slice.contains("old line"))
    }

    @Test
    fun newestOfBothMarkersWins() {
        // 同一个文件里前半段旧版本、后半段新版本：必须取最靠后的那个标记
        val text =
            "[12:00:00.000] ${DebugFileLog.LEGACY_SESSION_MARKER}\n" +
                "legacy line\n" +
                "[13:00:00.000] ${DebugFileLog.SESSION_MARKER}\n" +
                "current line\n"
        val slice = DebugFileLog.currentSessionSlice(text)
        assertTrue(slice.contains("current line"))
        assertTrue(!slice.contains("legacy line"))
    }
}
