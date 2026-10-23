package mignnlapin.traesignin

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import java.util.concurrent.CopyOnWriteArrayList
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import mignnlapin.traesignin.util.L

/**
 * 模块 App 进程入口：接通 LSPosed fork 服务层（对齐 dev.chaoxingdeadline/App.java）。
 *
 * XposedServiceHelper 依赖 patched service AAR 内的 XposedProvider（authority
 * ${applicationId}.XposedService，由 AAR manifest 合并注入），框架激活模块后
 * 会反向绑定本进程并回调 onServiceBind。
 */
class App : Application(), XposedServiceHelper.OnServiceListener {

    companion object {
        @Volatile
        var service: XposedService? = null
            private set

        private val listeners = CopyOnWriteArrayList<ServiceListener>()

        fun addServiceListener(listener: ServiceListener) {
            listeners.add(listener)
            listener.onServiceChanged(service)
        }

        fun removeServiceListener(listener: ServiceListener) {
            listeners.remove(listener)
        }
    }

    override fun onCreate() {
        super.onCreate()
        // App 进程日志文件持久化（files/logs/app_*.log，保留 5 天）：导出/排障不依赖 Trae
        L.init(this)
        // v2.0 深色键迁移：旧 dark_mode → dark_follow_system(默认关) + trae_dark_enabled（保持 Trae 深色原状态）
        run {
            val p = getSharedPreferences("trae_signin_prefs", 0)
            if (p.contains("dark_mode") && !p.contains("trae_dark_enabled")) {
                p.edit().putBoolean("trae_dark_enabled", p.getBoolean("dark_mode", false)).apply()
            }
        }
        applySavedTheme()
        XposedServiceHelper.registerListener(this)
        // 定时签到：App 启动即布防到点自动拉起 Trae 的闹钟
        SchedLauncher.arm(this)
        // 结果通知由模块自身身份发出：root 静默授予通知权限（失败则由设置页请求运行时权限兜底）
        Thread {
            try {
                Runtime.getRuntime().exec(
                    arrayOf("su", "-c", "pm grant mignnlapin.traesignin android.permission.POST_NOTIFICATIONS")
                ).waitFor()
            } catch (_: Throwable) { }
        }.start()
    }

    /** 深色主题：跟随系统开关（dark_follow_system）+ 手动选择（dark_force_dark） */
    private fun applySavedTheme() {
        val p = getSharedPreferences("trae_signin_prefs", 0)
        AppCompatDelegate.setDefaultNightMode(
            if (p.getBoolean("dark_follow_system", true)) {
                AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            } else if (p.getBoolean("dark_force_dark", false)) {
                AppCompatDelegate.MODE_NIGHT_YES
            } else {
                AppCompatDelegate.MODE_NIGHT_NO
            }
        )
    }

    override fun onServiceBind(service: XposedService) {
        App.service = service
        for (l in listeners) l.onServiceChanged(service)
    }

    override fun onServiceDied(service: XposedService) {
        App.service = null
        for (l in listeners) l.onServiceChanged(null)
    }

    interface ServiceListener {
        fun onServiceChanged(service: XposedService?)
    }
}
