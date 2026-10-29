package io.github.shaonialife.carptocar.util

import android.content.Context
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 全局调试文件日志（files/main_debug.log）。
 *
 * 荣耀机型 logcat 被 HKS 加密，本文件是 adb 侧唯一调试通道（run-as 读取）。
 * MainActivity 与 CarPlayService（投影生命周期诊断）共用同一把锁，避免同文件并发写交叉。
 */
object DebugFileLog {
    /**
     * 日志文件上限。
     *
     * 2026-10-29 从 2MB 提到 16MB：真车测试一次可能跑几十分钟，视频推流
     * （每秒 ~1 行 keepAlive/统计）+ iAP2 帧收发 + AirPlay 请求应答，2MB
     * 会被冲掉——**一冲掉就把会话开头的 `/info`、`0x4301`、音频协商全丢了**，
     * 而这些恰恰是事后定位最需要的部分。
     */
    private const val MAX_BYTES = 16_000_000L

    /**
     * 日志文件位置：**必须是内部存储**（`/data/data/<pkg>/files/main_debug.log`）。
     *
     * 2026-10-29：曾短期改到 `getExternalFilesDir`（为 `adb pull` 方便），随后回退。
     *
     * **更正一个错误归因**：当时把「切后台投屏 30 秒必断」归到了外部存储上，是错的。
     * 真因经系统日志确认是 MIUI 的 SmartPower 把进程降级成 cached：
     *   21:03:09 I/SmartPower: io.github.shaonialife.carptocar/10401(31955):
     *              background->idle(3000ms) R(become invisible) adj=200
     * adj=200 即 cached，进程被整体挂起（连 Log.i 都不输出），与日志写哪无关。
     *
     * 但**同步日志 I/O 在热路径上确实是隐患**：视频 30fps、iAP2 每帧、音频诊断
     * 每秒数十次 open→write→close 且持全局锁，存储一旦变慢就会拖住业务线程。
     * 所以路径回到内部存储 + 写入改异步（见 [append]），两条独立成立。
     *
     * 取日志用 root：
     *   adb shell su -c "cat /data/data/io.github.shaonialife.carptocar/files/main_debug.log"
     */
    private fun logFile(context: Context): File = File(context.filesDir, "main_debug.log")

    /** 日志文件的绝对路径（供 UI 显示 / 事后确认取哪个文件）。 */
    fun pathOf(context: Context): String = logFile(context).absolutePath

    private val lock = Any()
    private val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    /**
     * 「本次进程启动」标记行。日志文件跨进程保留（最多 2MB），用户反馈问题时
     * 历史轮次的日志会把真正有用的部分淹没——导出默认只取最后一条标记之后的内容。
     * 标记本身写进文件而不是只记内存偏移：文件可能被 2MB 截断，偏移会失效。
     */
    const val SESSION_MARKER = "===== CarPToCarL 进程启动（本次日志起点）====="

    /**
     * 改名前的标记（应用曾叫 CarDeck）。**故意保留旧字符串**：设备上可能存着旧版本写的
     * 日志文件，那时标记是旧名。只认新名会让这些文件退化成「无标记 = 返回全文」，
     * 用户导出历史日志时反而多出大量无关内容。
     */
    internal const val LEGACY_SESSION_MARKER = "===== CarDeck 进程启动（本次日志起点）====="

    /**
     * 写入本次启动标记。由 [io.github.shaonialife.carptocar.SenderApp.onCreate] 调用一次；
     * 重复调用会产生多条标记，导出仍取最后一条，行为安全。
     */
    fun markSessionStart(context: Context) {
        append(context, SESSION_MARKER)
    }

    /**
     * 读取日志正文。[currentSessionOnly] = true 时只返回最后一条 [SESSION_MARKER]
     * 之后的内容（无标记时返回全文——首次安装/旧版本文件都属这种情形）。
     */
    fun read(
        context: Context,
        currentSessionOnly: Boolean,
    ): String? =
        runCatching {
            // 等队列排空（最多 200ms），避免导出漏掉最后几行
            val deadline = System.currentTimeMillis() + 200
            while (queue.isNotEmpty() && System.currentTimeMillis() < deadline) {
                Thread.sleep(10)
            }
            val text = logFile(context).readText(Charsets.UTF_8)
            if (currentSessionOnly) currentSessionSlice(text) else text
        }.getOrNull()

    /**
     * 取最后一条启动标记之后的内容（纯函数，单测覆盖边界）。
     * 无标记 → 全文：旧版本写入的文件里没有标记，此时全量比空更好。
     */
    internal fun currentSessionSlice(text: String): String {
        // 新旧两种标记都认，取最靠后的那个：同一个文件里可能前半段是旧版本写的。
        val best =
            listOf(SESSION_MARKER, LEGACY_SESSION_MARKER)
                .map { marker -> marker to text.lastIndexOf(marker) }
                .filter { it.second >= 0 }
                .maxByOrNull { it.second }
                ?: return text
        return text.substring(best.second + best.first.length)
    }

    /** 待写队列。满即丢弃——日志永远不能拖慢音频/视频线程。 */
    private val queue = java.util.concurrent.LinkedBlockingQueue<String>(8192)

    @Volatile
    private var writerStarted = false

    /**
     * 追加一条日志。**非阻塞**：只入队，由单线程批量落盘。
     *
     * 为什么异步：原实现同步 `open → write → close` 且在 `synchronized` 内，热路径
     * （iAP2 收流 / CarLife 转发 / 视频发送）每秒要调几十次。存储一慢（磁盘忙、
     * 后台限流、FUSE），持锁线程被拖住、其它线程排队，音视频就会跟着卡。
     * 日志是观测手段，绝不能反噬被测系统 —— 这条是独立于任何具体 bug 的设计原则。
     */
    fun append(
        context: Context,
        message: String,
    ) {
        ensureWriter(context.applicationContext)
        // offer 不阻塞；队列满说明落盘严重滞后，丢弃比阻塞业务正确
        queue.offer(message)
    }

    private fun ensureWriter(ctx: Context) {
        if (writerStarted) return
        synchronized(lock) {
            if (writerStarted) return
            writerStarted = true
            Thread(
                {
                    val sb = StringBuilder(8192)
                    while (true) {
                        val first =
                            try {
                                queue.take()
                            } catch (_: InterruptedException) {
                                continue
                            }
                        sb.setLength(0)
                        appendLine(sb, first)
                        // 批量合并：一次 take 最多带 128 行一起写，降低开闭文件次数
                        var n = 1
                        while (n < 128) {
                            val more = queue.poll() ?: break
                            appendLine(sb, more)
                            n++
                        }
                        writeBatch(ctx, sb.toString())
                    }
                },
                "debug-file-log",
            ).apply {
                isDaemon = true
                start()
            }
        }
    }

    private fun appendLine(
        sb: StringBuilder,
        message: String,
    ) {
        sb.append('[').append(timestamp.format(Date())).append("] ").append(message).append('\n')
    }

    private fun writeBatch(
        ctx: Context,
        text: String,
    ) {
        try {
            val file = logFile(ctx)
            // 超限直接删会连「本次启动」标记一起丢（导出就退化为全量历史）。
            // 删后立即重写一条标记，保证截断后的新文件仍能按会话切分。
            if (file.length() > MAX_BYTES) {
                file.delete()
                FileWriter(file, true).use {
                    it
                        .append('[')
                        .append(timestamp.format(Date()))
                        .append("] ")
                        .append(SESSION_MARKER)
                        .append('\n')
                }
            }
            FileWriter(file, true).use { it.append(text) }
        } catch (_: Throwable) {
            // 落盘失败不影响业务
        }
    }
}
