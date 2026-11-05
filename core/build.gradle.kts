import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
}

// ---------------------------------------------------------------------------
// 应用元信息：与 Android/iOS 版本号同源于 gradle.properties，生成 commonMain 常量，
// 供 UI（关于页）与测试读取，避免界面另外维护一份版本号。
// ---------------------------------------------------------------------------
val generatedAppInfoDir = layout.buildDirectory.dir("generated/appInfo/commonMain/kotlin")

val generateAppInfo by tasks.registering {
    group = "version"
    description = "从 gradle.properties 生成 AppBuildInfo 常量"
    val versionName = providers.gradleProperty("valhalla.versionName").getOrElse("0.0.0")
    val versionCode = providers.gradleProperty("valhalla.versionCode").getOrElse("0")
    inputs.property("versionName", versionName)
    inputs.property("versionCode", versionCode)
    outputs.dir(generatedAppInfoDir)
    doLast {
        val file = generatedAppInfoDir.get().asFile.resolve("com/valhalla/core/AppBuildInfo.kt")
        file.parentFile.mkdirs()
        file.writeText(
            """
            // 由 :core:generateAppInfo 从 gradle.properties 生成，请勿手改。
            package com.valhalla.core

            /** 应用级元信息：版本号唯一来源是 gradle.properties。 */
            object AppBuildInfo {
                const val APP_NAME: String = "Valhalla"
                const val VERSION_NAME: String = "$versionName"
                const val VERSION_CODE: Int = $versionCode
                const val BUILD_CHANNEL: String = "stable"
            }

            """.trimIndent(),
        )
    }
}

kotlin.sourceSets.getByName("commonMain").kotlin.srcDir(generateAppInfo)
tasks.matching { it.name.startsWith("compileKotlin") }.configureEach {
    dependsOn(generateAppInfo)
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    iosX64()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
        androidMain.dependencies {
            implementation(libs.kotlinx.coroutines.android)
        }
        getByName("androidInstrumentedTest").apply {
            dependencies {
                implementation(libs.androidx.test.junit)
                implementation(libs.androidx.test.runner)
            }
        }
    }
}

android {
    namespace = "com.valhalla.core"
    compileSdk = libs.versions.androidCompileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.androidMinSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

/**
 * 导出基准：默认 5000 条 / 16 MiB 的 JSON 导出，输出耗时、吞吐与峰值缓冲。
 * 独立任务，不挂到 `check`/`jvmTest`，避免阻塞常规测试与 CI。
 */
val jvmTestCompilation = kotlin.targets.getByName("jvm").compilations.getByName("test")

tasks.register<JavaExec>("benchmarkExport") {
    group = "benchmark"
    description = "运行报文日志导出基准（5000 条 / 16 MiB），结果打印到控制台"
    dependsOn(tasks.named("jvmTestClasses"))
    classpath = jvmTestCompilation.output.allOutputs + jvmTestCompilation.runtimeDependencyFiles
    mainClass.set("com.valhalla.core.logging.PacketLogExportBenchmarkKt")
    jvmArgs("-Xmx1g")
}
