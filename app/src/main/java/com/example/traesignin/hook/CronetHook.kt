package com.example.traesignin.hook

import com.example.traesignin.XposedEntry
import com.example.traesignin.util.L
import io.github.libxposed.api.XposedInterface
import java.util.concurrent.ConcurrentHashMap

/**
 * Cronet/TTNet Hook —— Trae 手机端 API 流量走 TTNet（Cronet 封装），
 * HttpURLConnection/OkHttp hook 抓不到。TTNet 保留了 org.chromium.net 包名，
 * UrlRequestBuilderImpl 是所有请求 Builder 的汇聚点。
 *
 * dump 请求 URL + 全部请求头（含 Authorization / x-device-id），
 * 并通过 adb reverse 隧道（http://127.0.0.1:9999）回传 PC。
 */
object CronetHook : SubHook {

    private const val BUILDER_IMPL = "org.chromium.net.impl.UrlRequestBuilderImpl"
    private const val ENGINE_BASE = "org.chromium.net.impl.CronetEngineBase"

    /** builder 身份 → 已累计的 header，用于 build() 时统一输出 */
    private val headerBuckets = ConcurrentHashMap<Int, MutableList<String>>()

    /** 收获的敏感凭证（去重后回传 PC） */
    private val harvest = ConcurrentHashMap<String, String>()
    @Volatile private var harvestSent = false

    override fun install(ctx: HookContext) {
        val cl = ctx.classLoader
        val entry = XposedEntry.instance ?: run { L.e("CronetHook: entry instance null"); return }

        // 构造器：初始化 header bucket
        try {
            val builder = cl.loadClass(BUILDER_IMPL)
            for (c in builder.declaredConstructors) {
                entry.hook(c, object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        val r = chain.proceed()
                        headerBuckets[System.identityHashCode(chain.thisObject)] = mutableListOf()
                        return r
                    }
                })
            }
            L.i("  ✓ UrlRequestBuilderImpl 构造器追踪安装成功")
        } catch (t: Throwable) {
            L.e("  ✗ 构造器 Hook 失败: ${t.message}", t)
        }

        // 捕获 URL
        try {
            val base = cl.loadClass(ENGINE_BASE)
            var hooked = 0
            for (m in base.declaredMethods) {
                if (m.name == "newUrlRequestBuilder") {
                    entry.hook(m, object : XposedInterface.Hooker {
                        override fun intercept(chain: XposedInterface.Chain): Any? {
                            val url = chain.args.firstOrNull { it is String } as? String
                            if (url != null && isTraeUrl(url)) {
                                L.d("━━━ CRONET NEW REQUEST ━━━")
                                L.d("  URL: $url")
                            }
                            return chain.proceed()
                        }
                    })
                    hooked++
                }
            }
            L.i("  ✓ CronetEngine.newUrlRequestBuilder Hook 安装成功 ($hooked)")
        } catch (t: Throwable) {
            L.e("  ✗ CronetEngine Hook 失败: ${t.message}", t)
        }

        // 捕获每个请求头
        try {
            val builder = cl.loadClass(BUILDER_IMPL)
            var hooked = 0
            for (m in builder.declaredMethods) {
                if (m.name == "addHeader" && m.parameterTypes.size == 2 &&
                    m.parameterTypes[0] == String::class.java && m.parameterTypes[1] == String::class.java
                ) {
                    entry.hook(m, object : XposedInterface.Hooker {
                        override fun intercept(chain: XposedInterface.Chain): Any? {
                            val name = chain.getArg(0) as? String
                            val value = chain.getArg(1) as? String
                            if (name != null && value != null) {
                                val bucket = headerBuckets[System.identityHashCode(chain.thisObject)]
                                if (bucket != null) {
                                    bucket.add("$name: ${value.take(200)}")
                                } else if (isSensitiveHeader(name)) {
                                    L.d("  [Cronet header] $name: ${value.take(200)}")
                                }
                                harvestSensitive(name, value)
                            }
                            return chain.proceed()
                        }
                    })
                    hooked++
                }
            }
            L.i("  ✓ UrlRequestBuilderImpl.addHeader Hook 安装成功 ($hooked 个方法)")
        } catch (t: Throwable) {
            L.e("  ✗ UrlRequestBuilderImpl Hook 失败: ${t.message}", t)
        }

        // build() 时统一 dump：URL + headers
        try {
            val builder = cl.loadClass(BUILDER_IMPL)
            var hooked = false
            for (m in builder.declaredMethods) {
                if (m.name == "build" && m.parameterTypes.isEmpty()) {
                    entry.hook(m, object : XposedInterface.Hooker {
                        override fun intercept(chain: XposedInterface.Chain): Any? {
                            val key = System.identityHashCode(chain.thisObject)
                            val bucket = headerBuckets.remove(key)
                            if (bucket != null) {
                                val url = try {
                                    val f = chain.thisObject.javaClass.getDeclaredField("mUrl")
                                    f.isAccessible = true
                                    f.get(chain.thisObject) as? String
                                } catch (_: Throwable) { null }
                                if (url == null || isTraeUrl(url)) {
                                    L.d("━━━ CRONET REQUEST ━━━")
                                    L.d("  URL: ${url ?: "(unknown)"}")
                                    for (h in bucket) L.d("    $h")
                                    L.d("━━━ END CRONET REQUEST ━━━")
                                }
                            }
                            return chain.proceed()
                        }
                    })
                    hooked = true
                    break
                }
            }
            if (hooked) L.i("  ✓ UrlRequestBuilderImpl.build Hook 安装成功")
            else L.i("  - UrlRequestBuilderImpl.build 不存在，仅逐条 dump 敏感头")
        } catch (t: Throwable) {
            L.e("  ✗ build Hook 失败: ${t.message}", t)
        }
    }

    private fun isTraeUrl(url: String): Boolean =
        url.contains("trae.cn") || url.contains("trae.com.cn") || url.contains("zijieapi") ||
            url.contains("bytedance.net") || url.contains("mchost.guru")

    private fun isSensitiveHeader(name: String): Boolean {
        val n = name.lowercase()
        return n == "authorization" || n == "x-device-id" || n == "x-app-version" ||
            n == "x-device-brand" || n == "x-device-type" || n == "x-os-version" ||
            n == "user-agent" || n == "cookie" || n == "x-user-region" || n == "x-tt-token"
    }

    /** 收获敏感头 → 通过 adb reverse 隧道回传 PC（http://127.0.0.1:9999） */
    private fun harvestSensitive(name: String, value: String) {
        val n = name.lowercase()
        if (n != "authorization" && n != "x-device-id" && n != "x-app-version" && n != "user-agent") return
        if (value.isBlank()) return
        harvest[n] = value.take(1200)
        maybeExport()
    }

    private fun maybeExport() {
        if (harvestSent) return
        if (!harvest.containsKey("authorization") || !harvest.containsKey("x-device-id")) return
        harvestSent = true
        Thread {
            try {
                val body = org.json.JSONObject(harvest as Map<*, *>).toString()
                val bytes = body.toByteArray()
                L.i("harvest body size=${bytes.size} keys=${harvest.keys}")
                val conn = java.net.URL("http://127.0.0.1:9999/harvest").openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setFixedLengthStreamingMode(bytes.size)
                conn.outputStream.use { it.write(bytes); it.flush() }
                val code = conn.responseCode
                L.i("harvest exported: http $code")
                conn.disconnect()
            } catch (t: Throwable) {
                L.d("harvest export failed: ${t.message}")
                harvestSent = false  // 允许重试
            }
        }.start()
    }
}
