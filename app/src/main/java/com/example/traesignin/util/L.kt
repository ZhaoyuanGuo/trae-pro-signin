package com.example.traesignin.util

import android.util.Log
import com.example.traesignin.XposedEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object L {
    private const val TAG = "TraeSignIn"
    private const val MAX_LINES = 300

    /** 内存环形缓冲：HyperOS 下 shell 读不到 logcat，供模块 App 导出排障 */
    private val buffer = ArrayDeque<String>()
    private val lock = Any()

    // HyperOS 限制 shell 读 logcat，镜像到 libxposed log 以便在 LSPosed 日志页查看
    private fun bridge(msg: String) {
        try {
            XposedEntry.instance?.let { it.log(Log.INFO, TAG, msg) }
        } catch (_: Throwable) {
            // 模块自身进程无 Xposed 运行时，忽略
        }
    }

    private fun record(level: String, msg: String) {
        synchronized(lock) {
            if (buffer.size >= MAX_LINES) buffer.removeFirst()
            val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.CHINA).format(Date())
            buffer.addLast("$ts $level/$TAG: $msg")
        }
    }

    /** 导出缓冲内全部日志（时间升序，上限 MAX_LINES 条；严禁写入含 token 的内容） */
    fun dump(): String = synchronized(lock) {
        if (buffer.isEmpty()) "（暂无日志）" else buffer.joinToString("\n")
    }

    fun i(msg: String) { Log.i(TAG, msg); record("I", msg); bridge(msg) }
    fun d(msg: String) { Log.d(TAG, msg); record("D", msg); bridge(msg) }
    fun w(msg: String) { Log.w(TAG, msg); record("W", msg); bridge(msg) }
    fun e(msg: String, t: Throwable? = null) {
        if (t != null) Log.e(TAG, msg, t) else Log.e(TAG, msg)
        record("E", "$msg ${t?.message ?: ""}")
        bridge("$msg ${t?.message ?: ""}")
    }
}
