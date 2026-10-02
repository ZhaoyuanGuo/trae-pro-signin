package com.example.traesignin

import android.app.Application
import android.content.Context
import com.example.traesignin.hook.DarkModeHook
import com.example.traesignin.hook.HookLoader
import com.example.traesignin.util.L
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import java.lang.reflect.Executable

/**
 * libxposed API 102 模块入口（META-INF/xposed/java_init.list 声明）。
 *
 * 生命周期语义对齐 dev.chaoxingdeadline（modules.lsposed.org/module/dev.chaoxingdeadline/）：
 * - onModuleLoaded 仅打日志（含框架名与 API 版本）
 * - onPackageLoaded 对目标包 hook Application.attach 提前抓宿主 Context
 * - onPackageReady 安装主 hook（CronetHook / SignInHook）
 * - onHotReloading 无条件接受，状态恒为 "reload"
 * - onHotReloaded 仅解除旧 handle；重装依赖框架重放包回调（onPackageLoaded/onPackageReady）
 *
 * Trae 手机端包名：com.bytedance.trae.cn
 */
class XposedEntry : XposedModule(), XposedModuleInterface {

    companion object {
        @Volatile
        var instance: XposedEntry? = null
            private set

        const val TARGET_PKG = "com.bytedance.trae.cn"
    }

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        instance = this
        L.i("✅ hook loaded in ${param.processName}, framework $frameworkName API $apiVersion")
    }

    /** 包加载早期路径：抢在 Application 创建前挂 attach 钩子抓 Context */
    override fun onPackageLoaded(param: XposedModuleInterface.PackageLoadedParam) {
        if (param.packageName != TARGET_PKG) return
        installApplicationHook()
        // Force Dark opt-in 需抢在首屏 View 创建前（boot class 方法，此阶段即可 hook）
        DarkModeHook.installEarly(this)
    }

    /** 包就绪主路径：安装全部业务 hook */
    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        if (param.packageName != TARGET_PKG) return
        L.i("install hooks for ${param.packageName}")
        try {
            HookLoader.installAll(param.packageName, param.classLoader)
            L.i("✅ TraeSignIn hooks installed (onPackageReady)")
        } catch (t: Throwable) {
            L.e("❌ hooks install failed: ${t.message}", t)
        }
    }

    /** 热重载请求：无条件接受（对齐 chaoxing 语义） */
    override fun onHotReloading(param: XposedModuleInterface.HotReloadingParam): Boolean {
        param.setSavedInstanceState("reload")
        L.i("🔥 onHotReloading accepted")
        return true
    }

    /** 热重载完成：解除旧代 handle，重装由框架重放包回调完成 */
    override fun onHotReloaded(param: XposedModuleInterface.HotReloadedParam) {
        L.i("🔥 onHotReloaded, old handles=${param.oldHookHandles.size}")
        for (h in param.oldHookHandles) {
            try {
                h.unhook()
            } catch (t: Throwable) {
                L.w("旧 handle 解除失败: ${t.message}")
            }
        }
    }

    private fun installApplicationHook() {
        try {
            val attach = Application::class.java.getDeclaredMethod("attach", Context::class.java)
            hook(attach).setId("application_attach").intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val result = chain.proceed()
                    val arg = chain.getArg(0)
                    if (arg is Context) {
                        XposedEntryHolder.hostContext = arg.applicationContext
                        L.i("host context ready (Application.attach)")
                    }
                    return result
                }
            })
        } catch (t: Throwable) {
            L.e("hook Application.attach failed: ${t.message}", t)
        }
    }

    // ---- 提供给 hook 层的公共能力 ----

    fun hook(member: Executable, hooker: XposedInterface.Hooker): XposedInterface.HookHandle? =
        try {
            hook(member).intercept(hooker)
        } catch (t: Throwable) {
            L.e("hook failed: ${member.name} ${t.message}", t)
            null
        }
}

/** 宿主 Context 持有者：Application.attach 钩子写入，ActivityThread 反射兜底 */
object XposedEntryHolder {
    @Volatile var hostContext: Context? = null

    fun currentApp(): Context? = hostContext ?: try {
        val at = Class.forName("android.app.ActivityThread")
        at.getDeclaredMethod("currentApplication").invoke(null) as? Context
    } catch (_: Throwable) { null }
}
