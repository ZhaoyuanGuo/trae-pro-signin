package com.example.traesignin.hook

import android.os.Build
import com.example.traesignin.util.L

/**
 * 客户端伪装器（libxposed 版，仅保留无依赖的 Layer 1）。
 *
 * Layer 1 —— Build 静态字段伪装（纯 Android API，无 Xposed 依赖）
 * Layer 2/3（请求头改写）—— 待以 libxposed Hooker 实现：
 *   hook HttpURLConnection.setRequestProperty 与 Cronet addHeader（CronetHook 已有挂点）。
 *
 * 观察期默认禁用：Build 字段伪装会污染 aha 设备指纹，抓不到原生请求头。
 */
object ClientSpoofer : SubHook {

    private const val DESKTOP_BUILD_MODEL = "Trae-Windows-PC"
    private const val DESKTOP_MANUFACTURER = "ByteDance"
    private const val DESKTOP_BRAND = "Trae"
    private const val DESKTOP_HARDWARE = "desktop_x64"
    private const val DESKTOP_DEVICE = "TraeWorkstation"

    override fun install(ctx: HookContext) {
        L.i("ClientSpoofer: 安装 Layer 1 Build 字段伪装")
        val replacements = mapOf(
            "MODEL" to DESKTOP_BUILD_MODEL,
            "MANUFACTURER" to DESKTOP_MANUFACTURER,
            "BRAND" to DESKTOP_BRAND,
            "HARDWARE" to DESKTOP_HARDWARE,
            "DEVICE" to DESKTOP_DEVICE,
            "PRODUCT" to "trae_desktop_x64",
        )
        for ((fieldName, value) in replacements) {
            try {
                val f = Build::class.java.getDeclaredField(fieldName)
                f.isAccessible = true
                val old = f.get(null)
                f.set(null, value)
                L.i("  ✓ Build.$fieldName: '$old' → '$value'")
            } catch (t: Throwable) {
                L.d("  - Build.$fieldName 不可写（非致命）: ${t.message}")
            }
        }
    }
}
