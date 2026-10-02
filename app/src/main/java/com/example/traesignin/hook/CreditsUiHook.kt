package com.example.traesignin.hook

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import com.example.traesignin.XposedEntry
import com.example.traesignin.util.L
import io.github.libxposed.api.XposedInterface
import java.util.WeakHashMap

/**
 * Trae 积分处注入"立即签到"悬浮按钮。
 *
 * Trae 手机端 0.0.21 的设置面板（含积分行）是 Compose ModalBottomSheet，
 * 底层走 android.app.Dialog；且该版本积分行无点击事件、无独立积分 Activity。
 * 因此 hook Dialog.show()：弹层展示后遍历其无障碍树，命中"积分"文本
 * （即设置面板/积分相关弹层）才注入按钮——按钮随弹层出现与销毁。
 *
 * 按钮点击 → SignInEngine.runNow("manual")（同进程直接触发，无需经 60s 巡检）。
 * 另保留 Activity.onResume 关键词匹配，兼容未来版本出现独立积分页。
 */
object CreditsUiHook : SubHook {

    private const val TAG = "CreditsUiHook"

    /** 独立积分 Activity 类名关键词（未来版本兜底） */
    private val ACT_KEYWORDS = listOf("credit", "wallet", "point", "entitlement", "billing")

    /** 弹层注入去重（decorView 级） */
    private val injectedDecors = WeakHashMap<View, Boolean>()

    /** Activity 注入去重 */
    private val injectedActs = WeakHashMap<Activity, View>()

    /** 启动初期记录 Activity 类名，便于调参 */
    private var resumeLogged = 0
    private val main = Handler(Looper.getMainLooper())

    override fun install(ctx: HookContext) {
        val entry = XposedEntry.instance ?: run { L.e("CreditsUiHook: entry null"); return }

        // 主路径：Compose 弹层（设置面板/积分弹层）—— Dialog.show 后探测"积分"文本再注入
        try {
            val show = Dialog::class.java.getDeclaredMethod("show")
            entry.hook(show, object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val result = chain.proceed()
                    try {
                        val d = chain.thisObject as? Dialog ?: return result
                        val decor = d.window?.decorView ?: return result
                        // 弹层内容（Compose）异步挂载，多次探测
                        val delays = longArrayOf(400L, 900L, 1500L)
                        for (i in delays.indices) {
                            main.postDelayed({
                                try {
                                    if (!d.isShowing || injectedDecors[decor] == true) return@postDelayed
                                    val hit = when {
                                        // 优先：无障碍树精确探测"积分"文本
                                        else -> hasCreditsText(decor)
                                    }
                                    if (hit == true || (hit == null && isLargeSheet(decor))) injectIntoDialog(decor)
                                } catch (t: Throwable) {
                                    L.w("[$TAG] 弹层探测: ${t.message}")
                                }
                            }, delays[i])
                        }
                    } catch (t: Throwable) {
                        L.w("[$TAG] Dialog.show: ${t.message}")
                    }
                    return result
                }
            })
            L.i("  ✓ CreditsUiHook Dialog.show 安装成功")
        } catch (t: Throwable) {
            L.e("  ✗ Dialog.show Hook 失败: ${t.message}", t)
        }

        // 兜底：未来版本若有独立积分 Activity
        try {
            val onResume = Activity::class.java.getDeclaredMethod("onResume")
            entry.hook(onResume, object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val result = chain.proceed()
                    try {
                        val act = chain.thisObject as? Activity ?: return result
                        val name = act.javaClass.name
                        if (resumeLogged < 40) {
                            resumeLogged++
                            L.d("[$TAG] onResume: $name title=${act.title}")
                        }
                        val n = name.lowercase()
                        val title = act.title?.toString()?.lowercase() ?: ""
                        if (ACT_KEYWORDS.any { n.contains(it) } ||
                            title.contains("积分") || title.contains("credit") || title.contains("wallet")
                        ) injectIntoActivity(act)
                    } catch (t: Throwable) {
                        L.w("[$TAG] ${t.message}")
                    }
                    return result
                }
            })
            L.i("  ✓ CreditsUiHook Activity.onResume 安装成功")
        } catch (t: Throwable) {
            L.e("  ✗ Activity.onResume Hook 失败: ${t.message}", t)
        }
    }

    // ---- 弹层探测（无障碍树找"积分"） ----

    /**
     * BFS 遍历 decorView 无障碍树，查找"积分/credit/wallet"文本。
     * 返回 null = 树不可遍历（未 seal 等），调用方按尺寸启发式兜底。
     */
    private fun hasCreditsText(decor: View): Boolean? {
        return try {
            val root = decor.createAccessibilityNodeInfo() ?: return null
            seal(root)
            var found = false
            var visited = 0
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            queue.add(root)
            while (queue.isNotEmpty() && visited < 400 && !found) {
                val node = queue.removeFirst()
                visited++
                val t = node.text?.toString() ?: ""
                val cd = node.contentDescription?.toString() ?: ""
                if (t.contains("积分") || cd.contains("积分") ||
                    t.lowercase().contains("credit") || cd.lowercase().contains("credit")
                ) { found = true }
                val n = node.childCount
                for (i in 0 until n) {
                    node.getChild(i)?.let {
                        seal(it)
                        queue.add(it)
                    }
                }
            }
            L.d("[$TAG] 弹层探测 visited=$visited found=$found")
            // 进程内 createAccessibilityNodeInfo 不递归填充子树（childCount 恒 0），
            // visited<=2 说明树未展开 → 视为不可遍历，交由大尺寸启发式兜底
            if (visited <= 2) null else found
        } catch (t: Throwable) {
            L.w("[$TAG] 树遍历失败: ${t.message}")
            null
        }
    }

    /** hidden API：节点需 sealed 才允许 childCount/getChild */
    private fun seal(node: AccessibilityNodeInfo) {
        try {
            node.javaClass.getMethod("setSealed", Boolean::class.javaPrimitiveType!!)
                .invoke(node, true)
        } catch (_: Throwable) { }
    }

    /** 近全屏弹层（设置面板为接近满屏的 ModalBottomSheet；确认框等小弹窗排除） */
    private fun isLargeSheet(decor: View): Boolean = try {
        decor.height >= decor.resources.displayMetrics.heightPixels * 0.55f
    } catch (_: Throwable) { false }

    // ---- 注入 ----

    private fun injectIntoDialog(decor: View) {
        if (injectedDecors[decor] == true) return
        injectedDecors[decor] = true
        val vg = decor as? ViewGroup ?: return
        vg.post {
            try {
                addPill(vg, dp(vg.context, 24f), dp(vg.context, 130f))
                L.i("[$TAG] 已注入签到按钮 → 弹层 ${vg.javaClass.name}")
            } catch (t: Throwable) {
                L.w("[$TAG] 弹层注入失败: ${t.message}")
            }
        }
    }

    private fun injectIntoActivity(act: Activity) {
        if (injectedActs[act] != null) return
        act.runOnUiThread {
            try {
                if (act.isFinishing || act.isDestroyed || injectedActs[act] != null) return@runOnUiThread
                val decor = act.window?.decorView as? ViewGroup ?: return@runOnUiThread
                addPill(decor, dp(act, 20f), dp(act, 96f))
                injectedActs[act] = decor
                L.i("[$TAG] 已注入签到按钮 → ${act.javaClass.name}")
            } catch (t: Throwable) {
                L.w("[$TAG] 注入失败: ${t.message}")
            }
        }
    }

    /** 全屏透传容器 + 右下角胶囊按钮（不 clickable 的容器不拦截触摸） */
    private fun addPill(host: ViewGroup, endDp: Float, bottomDp: Float) {
        val ctx = host.context
        val btn = TextView(ctx).apply {
            text = "✨ 立即签到"
            textSize = 14f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(ctx, 24f)
                setColor(0xFF4D6BFE.toInt())
            }
            val padH = dp(ctx, 16f).toInt()
            val padV = dp(ctx, 11f).toInt()
            setPadding(padH, padV, padH, padV)
            elevation = dp(ctx, 6f)
            stateListAnimator = null
            setOnClickListener {
                Toast.makeText(ctx, "签到指令已触发", Toast.LENGTH_SHORT).show()
                SignInEngine.runNow("manual")
            }
        }
        val wrap = FrameLayout(ctx).apply {
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        wrap.addView(btn, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.END
        ).apply {
            marginEnd = endDp.toInt()
            bottomMargin = bottomDp.toInt()
        })
        host.addView(wrap, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
    }

    private fun dp(ctx: android.content.Context, v: Float): Float =
        v * ctx.resources.displayMetrics.density
}
