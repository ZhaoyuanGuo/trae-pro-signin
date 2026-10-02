# 强制模块入口类族进入主 dex（classes.dex）
# LSPosed fork 的模块加载器对 multidex 模块兼容性差，
# 参考正常工作的 API 101/102 模块均为单 dex 结构。
-keep class com.example.traesignin.** { *; }
