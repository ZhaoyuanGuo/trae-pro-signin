package com.example.traesignin

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import java.util.concurrent.CopyOnWriteArrayList
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper

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
        com.example.traesignin.cloud.RemoteBases.init(this)
        applySavedTheme()
        XposedServiceHelper.registerListener(this)
    }

    /** 深色主题开关（SettingsActivity ③）：开启 = 跟随系统深色模式；关闭 = 始终浅色 */
    private fun applySavedTheme() {
        val dark = getSharedPreferences("trae_signin_prefs", 0).getBoolean("dark_mode", false)
        AppCompatDelegate.setDefaultNightMode(
            if (dark) AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            else AppCompatDelegate.MODE_NIGHT_NO
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
