package com.example.traesignin.resolver

import com.example.traesignin.util.L

/**
 * 类解析器 —— STRICT → LOOSE → EXPERIMENTAL 三级兜底。
 * 可选使用，默认不依赖 DexKit。
 */
class DexKitResolver(
    private val classLoader: ClassLoader,
    private val strategy: Strategy = Strategy.STRICT
) {
    enum class Strategy { STRICT, LOOSE, EXPERIMENTAL }

    data class ResolutionResult(
        val success: Boolean,
        val source: Strategy,
        val clazz: Class<*>? = null,
        val candidates: Int = 0,
        val elapsedMs: Long = 0L,
        val note: String? = null,
    )

    fun resolve(className: String): ResolutionResult {
        val t0 = System.currentTimeMillis()

        val strict = tryStrict(className)
        if (strict.success) return strict.copy(elapsedMs = System.currentTimeMillis() - t0)

        val loose = tryLoose(className)
        if (loose.success) return loose.copy(elapsedMs = System.currentTimeMillis() - t0)

        return tryExperimental(className).copy(elapsedMs = System.currentTimeMillis() - t0)
    }

    private fun tryStrict(className: String): ResolutionResult {
        val t0 = System.currentTimeMillis()
        val pkgPrefixes = listOf("com.trae", "ai.trae", "com.trae.app", "")
        for (prefix in pkgPrefixes) {
            val full = if (prefix.isBlank()) className else "$prefix.$className"
            try {
                val c = Class.forName(full, false, classLoader)
                return ResolutionResult(true, Strategy.STRICT, c, 0, System.currentTimeMillis() - t0, "Class.forName")
            } catch (_: ClassNotFoundException) {}
        }
        return ResolutionResult(false, Strategy.STRICT, elapsedMs = System.currentTimeMillis() - t0)
    }

    private fun tryLoose(className: String): ResolutionResult {
        val t0 = System.currentTimeMillis()
        return try {
            val pathListField = java.lang.ClassLoader::class.java.getDeclaredField("pathList")
            pathListField.isAccessible = true
            val dexPathList = pathListField.get(classLoader)
            val dexElements = dexPathList.javaClass.getDeclaredField("dexElements").apply { isAccessible = true }.get(dexPathList) as Array<Any>
            var candidates = 0
            for (element in dexElements) {
                val dexFile = element.javaClass.getDeclaredField("dexFile").apply { isAccessible = true }.get(element) ?: continue
                val entriesMethod = dexFile.javaClass.getMethod("entries")
                @Suppress("UNCHECKED_CAST")
                val entries = entriesMethod.invoke(dexFile) as List<String>
                candidates += entries.filter { it.endsWith(className) }.size
            }
            ResolutionResult(false, Strategy.LOOSE, candidates = candidates, elapsedMs = System.currentTimeMillis() - t0)
        } catch (t: Throwable) {
            ResolutionResult(false, Strategy.LOOSE, elapsedMs = System.currentTimeMillis() - t0, note = "pathList 反射失败: ${t.message}")
        }
    }

    private fun tryExperimental(className: String): ResolutionResult {
        return ResolutionResult(false, Strategy.EXPERIMENTAL, note = "需 DexKit 支持（未集成）")
    }
}
