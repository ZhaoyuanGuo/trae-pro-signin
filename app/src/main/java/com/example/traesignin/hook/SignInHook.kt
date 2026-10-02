package com.example.traesignin.hook

import android.os.Handler
import android.os.Looper
import com.example.traesignin.XposedEntryHolder
import com.example.traesignin.util.L
import com.example.traesignin.util.Prefs

/**
 * 签到 Hook（libxposed API 102）。
 *
 * 主进程/推送进程均启动 SignInEngine：
 *   - 进程启动 15s 后补签当日漏签
 *   - 每分钟 tick 检查手动触发指令与失败重试
 *   - 每日 00:05 闹钟（进程存活时含 Doze 触发）
 *   - 跨进程去重：状态文件 + 尝试间隔护栏（见 SignInEngine）
 *
 * hook_mode 仅影响 CronetHook 的观察行为（EXPLORER/MONITOR）。
 */
object SignInHook : SubHook {

    override fun install(ctx: HookContext) {
        L.i("SignInHook: mode=${Prefs.hookMode()} 签到引擎启动中…")
        val context = XposedEntryHolder.currentApp()
        if (context != null) {
            SignInEngine.start(context)
            return
        }
        // Application.attach 钩子尚未触发（时序竞争），短暂等待重试
        val h = Handler(Looper.getMainLooper())
        var waited = 0
        fun tryStart() {
            val c = XposedEntryHolder.currentApp()
            if (c != null) {
                SignInEngine.start(c)
            } else if (waited < 30_000) {
                waited += 2_000
                h.postDelayed(::tryStart, 2_000)
            } else {
                L.e("SignInHook: 30s 内未取得宿主 Context，签到引擎未启动")
            }
        }
        h.postDelayed(::tryStart, 2_000)
    }
}
