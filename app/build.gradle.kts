import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// ---- 自动版本递增（每次 assemble 构建 versionCode+1、versionName 补丁位 +1）----
// 持久化在 app/version.properties（git 忽略）；测试/非 assemble 任务不递增。
val versionPropertiesFile = file("version.properties")
val versionProps = Properties()
if (versionPropertiesFile.exists()) {
    versionPropertiesFile.inputStream().use { versionProps.load(it) }
} else {
    versionProps["versionCode"] = "1"
    versionProps["versionName"] = "0.1.0"
}
val isAssemble = gradle.startParameter.taskNames.any { it.contains("assemble", ignoreCase = true) }
if (isAssemble) {
    versionProps["versionCode"] = ((versionProps["versionCode"] as String).toInt() + 1).toString()
    val nameParts = (versionProps["versionName"] as String).split(".")
    val patch = nameParts.getOrNull(2)?.toIntOrNull() ?: 0
    versionProps["versionName"] =
        "${nameParts.getOrElse(0) { "0" }}.${nameParts.getOrElse(1) { "1" }}.${patch + 1}"
    versionPropertiesFile.outputStream().use { versionProps.store(it, "auto-bumped on assemble build") }
}
val buildVersionCode = (versionProps["versionCode"] as String).toInt()
val buildVersionName = versionProps["versionName"] as String

// APK 重命名：构建后把 /apk/<variant>/app-<variant>.apk 复制为 DSH-Mobile-<variant>-v<versionName>.apk
// 复制前先删除同变体的旧命名 APK，避免累积、误拿旧版本包。
tasks.register("renameApks") {
    val base = layout.buildDirectory.get().asFile.absolutePath
    doLast {
        // 仅清理本次实际构建的变体：有 app-<variant>.apk 才删旧命名包并复新（避免误删未重建变体的旧包）
        listOf("debug", "release").forEach { variant ->
            val vDir = file("$base/outputs/apk/$variant")
            val baseApk = file("$vDir/app-$variant.apk")
            if (baseApk.exists()) {
                vDir.listFiles()
                    ?.filter { it.name.startsWith("DSH-Mobile-$variant-") && it.name.endsWith(".apk") }
                    ?.forEach { it.delete() }
                copy { from(baseApk); into(vDir); rename { "DSH-Mobile-$variant-v${buildVersionName}.apk" } }
            }
        }
    }
}
tasks.matching { it.name == "assembleDebug" || it.name == "assembleRelease" }.configureEach {
    finalizedBy("renameApks")
}

android {
    namespace = "dev.dshmobile"
    compileSdk = 35

    // release 签名：读 keystore/keystore.properties（git 忽略；缺失则降级用 debug 签名，便于无密钥环境构建）
    val keystoreProps = Properties().apply {
        val f = rootProject.file("keystore/keystore.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    val hasSign = keystoreProps.getProperty("storeFile") != null
    signingConfigs {
        if (hasSign) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    defaultConfig {
        applicationId = "dev.dshmobile"
        minSdk = 26
        targetSdk = 35
        versionCode = buildVersionCode
        versionName = buildVersionName

        // 私人部署值外置（app/personal.properties，git 忽略）：源码/开源构建不含任何私人域名。
        // 缺失时的中性兜底——默认服务器为空串（首次安装需在设置页填自己的地址）、
        // 模拟器调试 Host 改写权威用 loopback。
        val personalProps = Properties().apply {
            val f = file("personal.properties")
            if (f.exists()) f.inputStream().use { load(it) }
        }
        val defaultBaseUrl = personalProps.getProperty("DEFAULT_BASE_URL", "").trim()
        val trustedAuthority = personalProps.getProperty("TRUSTED_AUTHORITY", "127.0.0.1:3080").trim()
        buildConfigField("String", "DEFAULT_BASE_URL", "\"$defaultBaseUrl\"")
        buildConfigField("String", "TRUSTED_AUTHORITY", "\"$trustedAuthority\"")
    }

    // 无：APK 输出重命名改由下方 renameApks task（规避 AGP 8 variant API 兼容问题）

    buildTypes {
        release {
            // 发布版：R8 混淆 + 压缩（避免源码泄露），配正式签名
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (hasSign) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        // BuildConfig.DEBUG 供 DshApiClient 的模拟器测试 Host 改写拦截器使用（终审后实测辅助）
        buildConfig = true
    }
}

dependencies {
    // Compose
    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.core:core-ktx:1.13.1")

    // Network + serialization
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Persistence
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // 语音/图片：Coil 按 URL 加载 markdown 图片引用（[MarkdownText] 的 ![alt](url) 渲染）
    implementation("io.coil-kt:coil-compose:2.7.0")

    // Markdown 渲染（表格/图片/链接/HTML）：jeziellago/compose-markdown（JitPack）—— 替换自实现渲染器
    // 双库实测对比后选定（mikepenz 0.30 表格实测不生效，jeziellago 开箱即用全要素达标）
    implementation("com.github.jeziellago:compose-markdown:0.7.2")

    // Unit tests (JVM, same language as sources: Kotlin)
    testImplementation("org.jetbrains.kotlin:kotlin-test:2.0.20")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
