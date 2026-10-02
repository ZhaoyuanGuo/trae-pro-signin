package com.example.traesignin.hook

import com.example.traesignin.util.L

object HookLoader {
    private val hooks: List<SubHook> = listOf(
        // ClientSpoofer 观察期禁用：Build 字段伪装会污染 aha 设备指纹，抓不到原生请求头
        // ClientSpoofer,
        CronetHook,     // TTNet/Cronet 流量 dump（手机端主网络栈）
        SignInHook,
        CreditsUiHook,  // 积分页注入"立即签到"悬浮按钮
    )

    fun installAll(packageName: String, classLoader: ClassLoader) {
        val ctx = HookContext(packageName, packageName, classLoader)
        for (hook in hooks) {
            val name = hook.javaClass.simpleName
            try {
                hook.install(ctx)
                L.i("  ✅ $name 安装成功")
            } catch (t: Throwable) {
                L.e("  ❌ $name 安装失败: ${t.message}", t)
            }
        }
        // 深色主题：View 构造 hook 已在 onPackageLoaded 早期完成，此处应用夜间配置
        try {
            DarkModeHook.install()
            L.i("  ✅ DarkModeHook 夜间配置已应用")
        } catch (t: Throwable) {
            L.e("  ❌ DarkModeHook 安装失败: ${t.message}", t)
        }
    }
}

class HookContext(
    val packageName: String,
    val processName: String,
    val classLoader: ClassLoader,
)

interface SubHook {
    fun install(ctx: HookContext)
}
