# 保留所有 Xposed 相关类
-keep class de.robv.android.xposed.** { *; }
-keep class com.example.traesignin.** { *; }
-keep public class * extends io.github.libxposed.api.XposedModule {
    public <init>();
}
# patched service AAR：XposedProvider 由 manifest 合并声明，Binder 层保持稳定
-keep class io.github.libxposed.service.** { *; }
-dontwarn de.robv.android.xposed.**
-dontwarn io.github.libxposed.**
