package mignnlapin.traesignin.hook

import android.app.Activity
import android.app.Dialog
import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.Window
import mignnlapin.traesignin.XposedEntry
import mignnlapin.traesignin.XposedEntryHolder
import mignnlapin.traesignin.util.L
import io.github.libxposed.api.XposedInterface
import java.lang.ref.WeakReference

/**
 * Trae 深色主题（Hardware Layer 亮度反转路线）。
 *
 * 实机证据链（见 project_memory）：
 * - Trae 0.0.21 无 values-night、无主题设置项、无 JS/Bundle 主题层（UI 为原生 View + Compose）
 * - 夜间 per-app 配置系统级生效（进程 uiMode=33/NIGHT_YES 已实测）但界面不变色
 * - View 级 Force Dark opt-in（构造 hook setForceDarkAllowed）后仍不变色：
 *   HyperOS 4 的 HWUI 不对 Trae 的 Compose 内容做 Force Dark 变换 → 路线证伪
 *
 * 生效方案：对每个窗口（Activity/Dialog）decor 的直接子层挂 LAYER_TYPE_HARDWARE +
 * 亮度反转 ColorMatrix（luma invert = RGB 反转后补色相旋转 180°，色相保持、亮度镜像：
 * 白→黑、浅灰→深灰、品牌蓝→浅蓝紫）。作用于子树全部绘制内容（含 Compose）。
 *
 * 开关：trae_dark（remote prefs "trae_settings"，App 侧写入）。窗口 onResume/show 时
 * 按当前开关状态挂载/移除；开关状态变化时立即应用到所有存活窗口
 *（修复：此前仅在 onResume/show 时机应用，开关延迟就绪或切换后要等下次窗口回调才变色）。
 */
object DarkModeHook {

    private const val TAG = "DarkModeHook"
    private const val SYNC_INTERVAL_MS = 20_000L
    /** install 后的前几次快速同步（remote prefs 就绪时尽快拿到开关，避免首屏亮色等满 60s） */
    private val EARLY_SYNC_DELAYS_MS = longArrayOf(1_000L, 3_000L, 8_000L, 20_000L, 40_000L)

    /** 当前深色状态（同步循环更新；窗口回调时机读取） */
    @Volatile private var darkNow = false

    /** 存活 Activity 弱引用（onResume 时注册）：开关变化时立即应用到已打开的窗口 */
    private val activities = java.util.concurrent.CopyOnWriteArrayList<WeakReference<Activity>>()

    @Volatile private var lastAppliedMode: Int? = null
    private var syncStarted = false

    /** 亮度反转矩阵：RGB 反转 × 色相旋转 180°（luma 保持型反转） */
    private val invertPaint: Paint by lazy {
        val m = ColorMatrix(
            floatArrayOf(
                0.574f, -1.430f, -0.144f, 0f, 255f,
                -0.426f, -0.430f, -0.144f, 0f, 255f,
                -0.426f, -1.430f, 0.856f, 0f, 255f,
                0f, 0f, 0f, 1f, 0f,
            )
        )
        Paint().apply { colorFilter = ColorMatrixColorFilter(m) }
    }

    /** 早期阶段：View 全部构造 Force Dark opt-in（保留观察，某些原生页面可能受益） */
    fun installEarly(entry: XposedEntry) {
        if (Build.VERSION.SDK_INT < 31) return
        try {
            var hooked = 0
            for (c in View::class.java.constructors) {
                val ok = entry.hook(c, object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        val r = chain.proceed()
                        try {
                            (chain.thisObject as View).isForceDarkAllowed = true
                        } catch (_: Throwable) {
                        }
                        return r
                    }
                })
                if (ok != null) hooked++
            }
            L.i("[$TAG] ✓ View 构造 hook x$hooked（Force Dark opt-in）")
        } catch (t: Throwable) {
            L.e("[$TAG] ✗ View 构造 hook 失败: ${t.message}", t)
        }
    }

    /** 就绪阶段：窗口 hook + 夜间配置应用 + 同步循环 */
    fun install() {
        val entry = XposedEntry.instance ?: run { L.e("[$TAG] entry null"); return }

        // 开关文件预读：App 侧每次切换即 su 写入宿主 filesDir（同 uid 直接 File 读，
        // 无框架就绪延迟）。remote prefs 首读未就绪会误报 false，此前冷启动要等
        // 快速同步重试（实测 ~13s）才变色；预读后首屏即深色。
        readDarkFlag()?.let {
            darkNow = it
            L.i("[$TAG] 开关文件预读 → darkNow=$it")
        }

        // Activity 窗口：onResume 时机挂载/移除反转层
        try {
            entry.hook(Activity::class.java.getDeclaredMethod("onResume"), object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val r = chain.proceed()
                    try {
                        val act = chain.thisObject as? Activity
                        if (act != null) {
                            activities.removeAll { it.get() == null || it.get() === act }
                            activities.add(WeakReference(act))
                            // 打开 App 直接注入：onResume 实时读开关（remote prefs），
                            // 不依赖缓存的 darkNow，也不等周期同步
                            try { applyFromRemote() } catch (_: Throwable) { }
                            act.window?.let { applyToWindow(it) }
                        }
                    } catch (t: Throwable) {
                        L.w("[$TAG] onResume: ${t.message}")
                    }
                    return r
                }
            })
            L.i("[$TAG] ✓ Activity.onResume hook")
        } catch (t: Throwable) {
            L.e("[$TAG] ✗ onResume hook 失败: ${t.message}", t)
        }

        // Dialog 窗口（Compose 弹层）：show 后挂载
        try {
            entry.hook(Dialog::class.java.getDeclaredMethod("show"), object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val r = chain.proceed()
                    try {
                        (chain.thisObject as? Dialog)?.window?.let { applyToWindow(it) }
                    } catch (t: Throwable) {
                        L.w("[$TAG] Dialog.show: ${t.message}")
                    }
                    return r
                }
            })
            L.i("[$TAG] ✓ Dialog.show hook")
        } catch (t: Throwable) {
            L.e("[$TAG] ✗ Dialog.show hook 失败: ${t.message}", t)
        }

        applyFromRemote()
        registerRemoteListener()
        startSyncLoop()
    }

    /**
     * remote prefs 变化监听：App 侧切换深色开关后立即回调（及时注入的正解），
     * 周期同步仅作框架不支持监听时的兜底。
     */
    private fun registerRemoteListener() {
        try {
            XposedEntry.instance?.getRemotePreferences("trae_settings")
                ?.registerOnSharedPreferenceChangeListener { _, key ->
                    if (key != "trae_dark") return@registerOnSharedPreferenceChangeListener
                    Handler(Looper.getMainLooper()).post {
                        try {
                            applyFromRemote()
                            L.d("[$TAG] 监听器触发：trae_dark 变化已应用")
                        } catch (t: Throwable) {
                            L.w("[$TAG] 监听器应用异常: ${t.message}")
                        }
                    }
                }
            L.i("[$TAG] ✓ remote prefs 监听已注册")
        } catch (t: Throwable) {
            L.w("[$TAG] ✗ remote prefs 监听注册失败: ${t.message}")
        }
    }

    /** 按 darkNow 对窗口 decor 的直接子层挂/卸硬件层色彩矩阵 */
    private fun applyToWindow(win: Window) {
        val decor = win.decorView as? ViewGroup ?: return
        val count = decor.childCount
        for (i in 0 until count) {
            val child = decor.getChildAt(i)
            if (darkNow) child.setLayerType(View.LAYER_TYPE_HARDWARE, invertPaint)
            else child.setLayerType(View.LAYER_TYPE_NONE, null)
        }
        L.d("[$TAG] 窗口层 ${if (darkNow) "反转挂载" else "移除"} x$count")
    }

    private fun startSyncLoop() {
        if (syncStarted) return
        syncStarted = true
        val h = Handler(Looper.getMainLooper())
        // install 后 1s/3s/8s/20s/40s 快速同步，之后 60s 周期：
        // remote prefs 首次读取可能拿到未就绪的默认值 false，加速重读尽快进入深色
        for (delay in EARLY_SYNC_DELAYS_MS) {
            h.postDelayed({ try { applyFromRemote() } catch (t: Throwable) { L.w("[$TAG] 同步异常: ${t.message}") } }, delay)
        }
        h.postDelayed(object : Runnable {
            override fun run() {
                try {
                    applyFromRemote()
                } catch (t: Throwable) {
                    L.w("[$TAG] 同步异常: ${t.message}")
                }
                h.postDelayed(this, SYNC_INTERVAL_MS)
            }
        }, SYNC_INTERVAL_MS)
    }

    /** 开关状态变化时立即应用到所有存活 Activity 窗口（无需等待下一次 onResume） */
    private fun applyToAllAlive() {
        for (ref in activities) {
            val act = ref.get() ?: continue
            try {
                if (act.isDestroyed || act.isFinishing) continue
                act.window?.let { applyToWindow(it) }
            } catch (_: Throwable) { }
        }
    }

    /** 读宿主 filesDir 的开关文件（App 侧切换时 su 写入）：true/false；无文件或异常返回 null */
    private fun readDarkFlag(): Boolean? = try {
        val dir = XposedEntryHolder.currentApp()?.filesDir ?: return null
        when (java.io.File(dir, "trae_dark_flag").readText().trim()) {
            "1" -> true
            "0" -> false
            else -> null
        }
    } catch (_: Throwable) {
        null
    }

    /** 读 App 侧镜像开关（remote prefs trae_settings.trae_dark），应用 per-app 夜间配置 */
    private fun applyFromRemote() {
        // 开关文件为权威源（启动预读 + 实时读取均最快）；读不到（首次安装/旧版未写过）才回退 remote prefs
        var enabled: Boolean? = readDarkFlag()
        if (enabled == null) {
            XposedEntry.instance?.getRemotePreferences("trae_settings")?.let {
                enabled = try {
                    it.getBoolean("trae_dark", false)
                } catch (_: Throwable) {
                    null
                }
            }
        }
        val dark = enabled ?: return
        val changed = dark != darkNow
        darkNow = dark
        if (changed) applyToAllAlive()
        val ctx = XposedEntryHolder.currentApp() ?: return
        val mode = resolveMode(ctx, dark)
        if (mode == lastAppliedMode) return
        lastAppliedMode = mode
        setApplicationNightMode(ctx, mode)
    }

    /** 目标夜间模式：深色 = YES；否则按系统当前 uiMode 快照（UiModeManager 无 FOLLOW_SYSTEM 常量） */
    private fun resolveMode(ctx: Context, dark: Boolean): Int {
        if (dark) return UiModeManager.MODE_NIGHT_YES
        val nightNow = (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        return if (nightNow) UiModeManager.MODE_NIGHT_YES else UiModeManager.MODE_NIGHT_NO
    }

    private fun setApplicationNightMode(ctx: Context, mode: Int) {
        if (Build.VERSION.SDK_INT < 31) return
        try {
            val um = ctx.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager
            um.setApplicationNightMode(mode)
            L.i("[$TAG] Trae 夜间配置 → $mode")
        } catch (t: Throwable) {
            L.e("[$TAG] setApplicationNightMode 失败: ${t.message}", t)
        }
    }
}
