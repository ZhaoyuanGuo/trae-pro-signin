package mignnlapin.traesignin.util

import android.content.Context
import android.util.Log
import mignnlapin.traesignin.XposedEntry
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

object L {
    private const val TAG = "TraeSignIn"
    private const val MAX_LINES = 300
    /** hook 进程内：日志镜像到 remote prefs 的最小间隔（供 Trae 未运行时 App 侧导出） */
    private const val RING_FLUSH_MS = 5_000L
    /** App 进程文件日志保留天数 */
    private const val PERSIST_KEEP_DAYS = 5L

    /** 内存环形缓冲：HyperOS 下 shell 读不到 logcat，供模块 App 导出排障 */
    private val buffer = ArrayDeque<String>()
    private val lock = Any()
    @Volatile private var lastRingFlushAt = 0L

    private var appCtx: Context? = null
    private val persistExecutor = Executors.newSingleThreadExecutor()
    private val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)

    /** App 进程调用（App.onCreate）：启用文件持久化（files/logs/app_YYYY-MM-DD.log，保留 5 天） */
    fun init(context: Context) {
        appCtx = context.applicationContext
    }

    /**
     * 宿主 hook 进程判定：模块 App 进程没有 Xposed 运行时（api 为 compileOnly 依赖，
     * XposedEntry 继承的 XposedModule 类不存在），裸读 XposedEntry.instance 触发类
     * 加载即抛 NoClassDefFoundError（App 进程直签的日志链路曾因此崩溃）。
     * 先探测框架类可加载，App 进程一律跳过 hook 通道，只写内存缓冲、文件与 logcat。
     */
    private val hookEnvReady: Boolean by lazy {
        try { Class.forName("io.github.libxposed.api.XposedModule"); true }
        catch (_: Throwable) { false }
    }

    // HyperOS 限制 shell 读 logcat，镜像到 libxposed log 以便在 LSPosed 日志页查看
    private fun bridge(msg: String) {
        if (!hookEnvReady) return
        try {
            XposedEntry.instance?.let { it.log(Log.INFO, TAG, msg) }
        } catch (_: Throwable) {
        }
    }

    private fun record(level: String, msg: String) {
        val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.CHINA).format(Date())
        val line = "$ts $level/$TAG: $msg"
        synchronized(lock) {
            if (buffer.size >= MAX_LINES) buffer.removeFirst()
            buffer.addLast(line)
        }
        // App 进程：落到软件自身存储（files/logs/，不依赖 Trae 运行）；hook 进程：remote prefs 镜像
        if (!hookEnvReady) persistAsync(line)
        flushRingIfNeeded()
    }

    /** App 进程文件落盘：单线程异步 append，每日轮转，删除 5 天前的文件 */
    private fun persistAsync(line: String) {
        val ctx = appCtx ?: return
        persistExecutor.execute {
            try {
                val dir = File(ctx.filesDir, "logs").apply { mkdirs() }
                val today = dayFmt.format(Date())
                File(dir, "app_$today.log").appendText(line + "\n")
                val cutoff = dayFmt.format(Date(System.currentTimeMillis() - PERSIST_KEEP_DAYS * 86_400_000L))
                dir.listFiles()?.forEach { f ->
                    val m = Regex("""app_(\d{4}-\d{2}-\d{2})\.log""").find(f.name) ?: return@forEach
                    if (m.groupValues[1] < cutoff) f.delete()
                }
            } catch (_: Throwable) {
            }
        }
    }

    /** 读 App 侧持久化日志（全部保留文件按时间正序拼接；无文件返回空串） */
    fun readPersisted(): String = try {
        val dir = appCtx?.let { File(it.filesDir, "logs") }
        val files = dir?.listFiles { f -> f.name.matches(Regex("""app_\d{4}-\d{2}-\d{2}\.log""")) }
            ?.sortedBy { it.name } ?: emptyList()
        files.joinToString("\n") { f ->
            try { f.readText().trimEnd() } catch (_: Throwable) { "" }
        }
    } catch (_: Throwable) {
        ""
    }

    /**
     * 日志防抖镜像到 remote prefs（trae_state.log_ring）：数据存于 LSPosed 框架侧，
     * 不写宿主目录；Trae 进程被杀后模块 App 仍可经框架通道读出最近日志。
     */
    private fun flushRingIfNeeded() {
        if (!hookEnvReady) return
        val inst = XposedEntry.instance ?: return
        val now = System.currentTimeMillis()
        synchronized(lock) {
            if (now - lastRingFlushAt < RING_FLUSH_MS) return
            lastRingFlushAt = now
        }
        try {
            inst.getRemotePreferences("trae_state")
                .edit()
                .putString("log_ring", dump())
                .putLong("log_ring_at", now)
                .apply()
        } catch (_: Throwable) {
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
