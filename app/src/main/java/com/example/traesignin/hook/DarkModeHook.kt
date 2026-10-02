package com.example.traesignin.hook

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
import com.example.traesignin.XposedEntry
import com.example.traesignin.XposedEntryHolder
import com.example.traesignin.util.L
import io.github.libxposed.api.XposedInterface

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
 * 按当前开关状态挂载/移除；60s 周期同步开关与系统模式。
 */
object DarkModeHook {

    private const val TAG = "DarkModeHook"
    private const val SYNC_INTERVAL_MS = 60_000L

    /** 当前深色状态（60s 同步循环更新；窗口回调时机读取） */
    @Volatile private var darkNow = false

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

        // Activity 窗口：onResume 时机挂载/移除反转层
        try {
            entry.hook(Activity::class.java.getDeclaredMethod("onResume"), object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val r = chain.proceed()
                    try {
                        (chain.thisObject as? Activity)?.window?.let { applyToWindow(it) }
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
        startSyncLoop()
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

    /** 读 App 侧镜像开关（remote prefs trae_settings.trae_dark），应用 per-app 夜间配置 */
    private fun applyFromRemote() {
        var enabled: Boolean? = null
        XposedEntry.instance?.getRemotePreferences("trae_settings")?.let {
            enabled = try {
                it.getBoolean("trae_dark", false)
            } catch (_: Throwable) {
                null
            }
        }
        val dark = enabled ?: return
        darkNow = dark
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
