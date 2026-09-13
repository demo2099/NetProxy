import com.android.build.api.variant.FilterConfiguration
import java.io.File
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// 签名材料，按优先级查找（只取第一个存在的）：
//   1. signing.properties —— 本地手写 / CI 从 secrets 还原（gitignored）
//   2. keystore/signing.properties —— 可选，随仓库分发，让 CI 不配 secrets
//      也能用**同一个密钥**出正式签名包（覆盖安装的前提）
val signingProps = Properties().apply {
    listOf(rootProject.file("signing.properties"), rootProject.file("keystore/signing.properties"))
        .firstOrNull { it.exists() }
        ?.inputStream()?.use { load(it) }
}

/** 有可用签名材料 = 真正的正式签名（跨版本覆盖安装的前提）。 */
val releaseStoreFile: File? = signingProps.getProperty("storeFile")
    ?.let { rootProject.file(it) }
    ?.takeIf { it.isFile }

// version.properties — 全局唯一的版本定义处 (设置页与 CI 的 tag 校验都依赖它)
val versionProps = Properties().apply {
    rootProject.file("version.properties").inputStream().use { load(it) }
}

android {
    namespace = "com.interstellar.proxy"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.interstellar.proxy"
        minSdk = 24
        targetSdk = 36
        versionCode = versionProps.getProperty("versionCode").toInt()
        versionName = versionProps.getProperty("versionName")
    }

    signingConfigs {
        create("release") {
            val store = releaseStoreFile
            if (store != null) {
                storeFile = store
                storePassword = signingProps.getProperty("storePassword")
                keyAlias = signingProps.getProperty("keyAlias")
                keyPassword = signingProps.getProperty("keyPassword")
                // 必须显式写死：JDK 9+ 的 KeyStore 默认类型是 PKCS12，AGP 不指定
                // 时用默认类型，跟 .jks 不匹配就会报
                // "Keystore was tampered with, or password was incorrect"
                storeType = signingProps.getProperty("storeType") ?: "JKS"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // 千万别留空：signingConfig 为 null 时 AGP 产出的是**未签名** APK，
            // 装到手机上直接报「安装包未包含任何证书」。没有正式签名材料时退回
            // debug 签名，至少保证产物能装（代价：签名随 CI 机器变化，不能覆盖
            // 安装 —— 文件名会带 -debugsigned 提醒）。
            signingConfig = if (releaseStoreFile != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
        // debug builds get their own applicationId so 地心游记 (debug) and
        // 星际穿越 (release) coexist on the same device
        debug {
            applicationIdSuffix = ".debug"
            if (releaseStoreFile != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    // 两套产物，同一个 applicationId（可互相覆盖升级）：
    //   slim = 仅 sing-box 内核，体积最小
    //   full = 另含 mihomo / Xray，可在界面「内核」里切换
    // sidecar 的 .so 与 v2fly geodata 只放在 app/src/full/ 下（见 tools/fetch_cores.py），
    // 所以 slim 天然不会打进它们 —— 不需要在打包阶段做排除。
    flavorDimensions += "cores"
    productFlavors {
        create("slim") {
            dimension = "cores"
            buildConfigField("String", "CORE_KINDS", "\"singbox\"")
        }
        create("full") {
            dimension = "cores"
            buildConfigField("String", "CORE_KINDS", "\"singbox,mihomo,xray\"")
        }
    }

    // per-ABI APKs: ~40MB instead of one 146MB universal blob
    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
            isUniversalApk = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    buildFeatures {
        aidl = true
        // 设置页"版本"行读取 BuildConfig.VERSION_NAME
        buildConfig = true
    }
}

// APK 输出统一以 interstellar 开头：interstellar-<flavor>-<abi>-<buildType>.apk
// 例如 interstellar-slim-arm64-v8a-release.apk / interstellar-full-universal-debug.apk
// 没有正式签名材料时补 -debugsigned：AGP 本来会给未签名产物加 -unsigned，但下面这个
// set() 会把名字整个覆盖掉 —— 一个装不上的包就会长得跟正常包一模一样（踩过）。
androidComponents {
    onVariants { variant ->
        val flavor = variant.productFlavors.joinToString("-") { it.second }
        val signTag = if (releaseStoreFile == null && variant.buildType == "release") "-debugsigned" else ""
        variant.outputs.forEach { output ->
            val abi = output.filters
                .firstOrNull { it.filterType == FilterConfiguration.FilterType.ABI }
                ?.identifier
            output.outputFileName.set(
                "interstellar-${flavor}-${abi ?: "universal"}-${variant.buildType}${signTag}.apk"
            )
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // sing-box core (built via `make lib_android` from the sing-box source)
    if (File(projectDir, "libs/libbox.aar").exists()) {
        implementation(files("libs/libbox.aar"))
    }

    val composeBom = platform("androidx.compose:compose-bom:2026.06.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.navigation:navigation-compose:2.9.5")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.9.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("androidx.datastore:datastore-preferences:1.1.7")
    implementation("androidx.work:work-runtime-ktx:2.11.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.charleskorn.kaml:kaml:0.104.0")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
}
