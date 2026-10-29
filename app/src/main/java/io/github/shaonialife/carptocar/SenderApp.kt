package io.github.shaonialife.carptocar

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import io.github.shaonialife.carptocar.launcher.VirtualLauncher
import io.github.shaonialife.carptocar.util.DebugFileLog
import io.github.shaonialife.carptocar.util.SessionTrace

/**
 * 进程级安全网：任一线程的未捕获异常只记录并让该线程结束，
 * 不再走默认处理器杀死整个进程（连接会话横跨多个后台线程，
 * 一个套接字异常不应毁掉全部链路与前台服务）。
 * 各线程自身仍应尽量用 try/catch 精确处理；这里是最后防线。
 */
class SenderApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // 会话日志起点标记：日志文件跨进程累积（2MB 上限），导出默认只取本次启动
        // 之后的部分。必须在这里写（Application 是进程最早入口），晚于任何业务日志
        // 都会让它被算进「历史」。
        DebugFileLog.markSessionStart(this)
        // OPUS 编码器可用性自检（导航独立流 type=101 用 OPUS，见 OpusEncoder）。
        // 进程启动即探一次：编码器能不能真编出包，不该等到连上车机才发现。
        // 只 DEBUG 构建跑，避免正式包每次冷启动都创建一次 MediaCodec。
        if (BuildConfig.DEBUG) {
            Thread(
                {
                    try {
                        Log.i(TAG, "OPUS 自检 = ${io.github.shaonialife.carptocar.audio.OpusEncoder.probe()}")
                    } catch (error: Throwable) {
                        Log.w(TAG, "OPUS 自检异常 - ${error.message}")
                    }
                },
                "opus-probe",
            ).apply { isDaemon = true }.start()
        }
        // 完整会话报文录制（files/session_trace.log）：与上面的过程日志分开两份文件，
        // 原因见 SessionTrace 注释（报文量大会挤掉过程日志）。必须在业务起来之前 attach。
        SessionTrace.attach(this)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(TAG, "未捕获异常（线程 ${thread.name}，已吞掉保进程）", throwable)
            // 不调用 previous：默认处理器会 killProcess 杀死整个 app
        }
        // 应用安装/卸载/更新 → 桌面与车机设置的共享应用清单缓存失效
        val packageFilter =
            IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addDataScheme("package")
            }
        registerReceiver(
            object : BroadcastReceiver() {
                override fun onReceive(
                    context: Context,
                    intent: Intent,
                ) {
                    VirtualLauncher
                        .invalidateAppsCache()
                    Log.i(TAG, "应用清单缓存已失效（包变化）")
                }
            },
            packageFilter,
        )
    }

    companion object {
        private const val TAG = "CarPlaySender"
    }
}
