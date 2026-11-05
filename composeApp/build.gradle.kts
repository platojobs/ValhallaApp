import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

// ---------------------------------------------------------------------------
// 版本号：唯一来源是 gradle.properties 的 valhalla.versionName / valhalla.versionCode。
// Android 直接读取；iOS 通过生成的 Version.xcconfig 读取同一组值。
// ---------------------------------------------------------------------------
val appVersionName: String = providers.gradleProperty("valhalla.versionName").getOrElse("2.2.0")
val appVersionCode: Int = providers.gradleProperty("valhalla.versionCode").getOrElse("22").toInt()

// ---------------------------------------------------------------------------
// Release 签名：从环境变量或本地 keystore.properties 读取，不硬编码密钥。
// 全部缺失时允许产出未签名 APK（并给出警告）；只配置了一部分则直接报错。
// ---------------------------------------------------------------------------
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}

fun signingValue(propertyKey: String, envKey: String): String? =
    System.getenv(envKey)?.takeIf { it.isNotBlank() }
        ?: keystoreProperties.getProperty(propertyKey)?.takeIf { it.isNotBlank() }

val signingStorePath = signingValue("storeFile", "VALHALLA_KEYSTORE_PATH")
val signingStorePassword = signingValue("storePassword", "VALHALLA_KEYSTORE_PASSWORD")
val signingKeyAlias = signingValue("keyAlias", "VALHALLA_KEY_ALIAS")
val signingKeyPassword = signingValue("keyPassword", "VALHALLA_KEY_PASSWORD")
val signingValues = listOf(signingStorePath, signingStorePassword, signingKeyAlias, signingKeyPassword)
val signingComplete = signingValues.all { it != null }
val signingPartial = signingValues.any { it != null } && !signingComplete

if (signingPartial) {
    throw GradleException(
        "Android release 签名配置不完整。请同时提供以下四项（环境变量或 keystore.properties）：" +
            " VALHALLA_KEYSTORE_PATH(storeFile)、VALHALLA_KEYSTORE_PASSWORD(storePassword)、" +
            "VALHALLA_KEY_ALIAS(keyAlias)、VALHALLA_KEY_PASSWORD(keyPassword)。",
    )
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "ValhallaApp"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":core"))
            implementation(project(":data"))
            implementation(project(":feature"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
        }
        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
        }
    }
}

android {
    namespace = "com.valhalla.app"
    compileSdk = libs.versions.androidCompileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.valhalla.app"
        minSdk = libs.versions.androidMinSdk.get().toInt()
        targetSdk = libs.versions.androidTargetSdk.get().toInt()
        versionCode = appVersionCode
        versionName = appVersionName
    }

    signingConfigs {
        if (signingComplete) {
            create("release") {
                storeFile = rootProject.file(signingStorePath!!)
                storePassword = signingStorePassword
                keyAlias = signingKeyAlias
                keyPassword = signingKeyPassword
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (signingComplete) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

if (!signingComplete) {
    logger.warn(
        "Android release 未配置签名：assembleRelease 将产出未签名 APK（不可直接分发）。" +
            "配置 VALHALLA_KEYSTORE_PATH 等环境变量或 keystore.properties 后可生成签名包，详见 README。",
    )
}

// ---------------------------------------------------------------------------
// iOS 版本同步：从 gradle.properties 生成 Version.xcconfig（Xcode 工程通过
// baseConfigurationReference 读取）。文件需随仓库提交，`verifyVersionConsistency`
// 会校验它与 gradle.properties 未漂移。
// ---------------------------------------------------------------------------
val iosVersionConfig = rootProject.layout.projectDirectory.file("iosApp/iosApp/Version.xcconfig").asFile

tasks.register("syncIosVersion") {
    group = "version"
    description = "从 gradle.properties 生成 iosApp 的 Version.xcconfig"
    val versionName = appVersionName
    val versionCode = appVersionCode
    inputs.property("versionName", versionName)
    inputs.property("versionCode", versionCode)
    outputs.file(iosVersionConfig)
    doLast {
        iosVersionConfig.parentFile.mkdirs()
        iosVersionConfig.writeText(
            buildString {
                appendLine("// 由 :composeApp:syncIosVersion 从 gradle.properties 生成，请勿手改。")
                appendLine("MARKETING_VERSION = $versionName")
                appendLine("CURRENT_PROJECT_VERSION = $versionCode")
            },
        )
    }
}

tasks.register("verifyVersionConsistency") {
    group = "verification"
    description = "校验 Android/iOS 版本号来自 gradle.properties 的同一来源"
    doLast {
        check(appVersionName.matches(Regex("""\d+\.\d+\.\d+"""))) {
            "gradle.properties 的 valhalla.versionName 不是 x.y.z：$appVersionName"
        }
        check(appVersionCode > 0) { "gradle.properties 的 valhalla.versionCode 必须为正数" }

        check(iosVersionConfig.exists()) {
            "缺少 ${iosVersionConfig.path}；请先运行 ./gradlew :composeApp:syncIosVersion"
        }
        val config = iosVersionConfig.readText()
        fun xcconfigValue(key: String): String? =
            Regex("""^$key\s*=\s*(\S+)""", RegexOption.MULTILINE).find(config)?.groupValues?.get(1)

        check(xcconfigValue("MARKETING_VERSION") == appVersionName) {
            "Version.xcconfig 的 MARKETING_VERSION 与 gradle.properties 不一致"
        }
        check(xcconfigValue("CURRENT_PROJECT_VERSION") == appVersionCode.toString()) {
            "Version.xcconfig 的 CURRENT_PROJECT_VERSION 与 gradle.properties 不一致"
        }

        val infoPlist = rootProject.file("iosApp/iosApp/Info.plist").readText()
        check(infoPlist.contains("\$(MARKETING_VERSION)")) {
            "Info.plist 的 CFBundleShortVersionString 应引用 \$(MARKETING_VERSION)"
        }
        check(infoPlist.contains("\$(CURRENT_PROJECT_VERSION)")) {
            "Info.plist 的 CFBundleVersion 应引用 \$(CURRENT_PROJECT_VERSION)"
        }

        val androidScript = layout.projectDirectory.file("build.gradle.kts").asFile.readText()
        check(androidScript.contains("valhalla.versionName") && androidScript.contains("valhalla.versionCode")) {
            "composeApp/build.gradle.kts 应从 gradle.properties 读取版本号，而不是硬编码"
        }
        logger.lifecycle("版本一致性校验通过：$appVersionName (code $appVersionCode)")
    }
}

tasks.register("verifyReleaseArtifact") {
    group = "verification"
    description = "构建 release 并校验 APK 产物存在（未配置签名时给出警告）"
    dependsOn(tasks.named("assembleRelease"))
    doLast {
        val apkDir = layout.buildDirectory.dir("outputs/apk/release").get().asFile
        val apks = apkDir.listFiles { file -> file.extension == "apk" }.orEmpty().sortedBy { it.name }
        check(apks.isNotEmpty()) { "未找到 release APK：${apkDir.path}" }
        val unsigned = apks.filter { it.name.contains("unsigned") }
        if (signingComplete) {
            check(unsigned.isEmpty()) { "签名已配置，但产物仍为未签名：${unsigned.map { it.name }}" }
            logger.lifecycle("release 产物：${apks.joinToString { it.name }}（已签名）")
        } else {
            logger.warn("release 产物：${apks.joinToString { it.name }}（未签名，仅用于本地验证）")
        }
    }
}
