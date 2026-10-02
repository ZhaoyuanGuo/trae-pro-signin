import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// release 签名：凭据存于 gitignored 的 keystore.properties，勿入库
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.example.traesignin"
    // patched AAR 的 aar-metadata 要求 minCompileSdk=36（对齐 chaoxingdeadline）
    compileSdk = 36

    signingConfigs {
        create("release") {
            if (keystoreProps.isNotEmpty()) {
                storeFile = file(keystoreProps["storeFile"] as String)
                storePassword = keystoreProps["storePassword"] as String
                keyAlias = keystoreProps["keyAlias"] as String
                keyPassword = keystoreProps["keyPassword"] as String
            }
        }
    }

    defaultConfig {
        applicationId = "io.github.zhaoyuanguo.traesignin"
        // patched AAR 要求 minSdk 26（对齐 chaoxingdeadline；目标设备 Android 17）
        minSdk = 26
        targetSdk = 35
        versionCode = 9
        versionName = "1.0.8"
    }

    buildTypes {
        release {
            if (keystoreProps.isNotEmpty()) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = false
            multiDexKeepProguard = file("maindex-rules.pro")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = true
            isDebuggable = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            merges += "META-INF/xposed/*"
            excludes += "**"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("com.google.android.material:material:1.12.0")

    // 友爱账号体系（Uniai）+ 代签（OkHttp 成熟稳定；security-crypto 提供加密存储）
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    compileOnly("de.robv.android.xposed:api:82")
    compileOnly("de.robv.android.xposed:api:82:sources")
    compileOnly("io.github.libxposed:api:102.0.0")

    // LSPosed fork (Miuix) 服务层：模块 App ↔ 框架双向通道（getRunningTargets / hotReloadModule / RemotePreferences）
    // interface = Binder IPC 层（IXposedService / IHotReloadCallback），service = 公共服务层（XposedService / XposedProvider）
    // 以 implementation 打包进 APK（与 dev.chaoxingdeadline 相同的做法），compileOnly 的 api 102 仍由框架运行时提供
    implementation(files("libs/interface-102.0.0-patched.aar"))
    implementation(files("libs/service-102.0.0-patched.aar"))
}
