import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.detekt)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.kover)
}

// ---------------------------------------------------------------------------
// 应用元信息：与 Android/iOS 版本号同源于 gradle.properties，生成 commonMain 常量，
// 供 UI（关于页）与测试读取，避免界面另外维护一份版本号。
// ---------------------------------------------------------------------------
val generatedAppInfoDir = layout.buildDirectory.dir("generated/appInfo/commonMain/kotlin")

val generateAppInfo by tasks.registering {
    group = "version"
    description = "从 gradle.properties、环境变量与依赖版本表生成 AppBuildInfo / AppDependencyInfo"
    val versionName = providers.gradleProperty("valhalla.versionName").getOrElse("0.0.0")
    val versionCode = providers.gradleProperty("valhalla.versionCode").getOrElse("0")
    val buildChannel = providers.environmentVariable("VALHALLA_BUILD_CHANNEL").getOrElse("stable")
    val envCommit = providers.environmentVariable("VALHALLA_BUILD_COMMIT")
    val envSha = providers.environmentVariable("GITHUB_SHA")
    val kotlinVersion = libs.versions.kotlin.get()
    val composeVersion = libs.versions.composeMultiplatform.get()
    val coroutinesVersion = libs.versions.coroutines.get()
    val activityComposeVersion = libs.versions.activityCompose.get()
    val androidxCoreVersion = libs.versions.androidxCore.get()
    val androidxTestJunitVersion = libs.versions.androidxTestJunit.get()
    val androidxTestRunnerVersion = libs.versions.androidxTestRunner.get()
    val agpVersion = libs.versions.agp.get()
    inputs.property("versionName", versionName)
    inputs.property("versionCode", versionCode)
    inputs.property("buildChannel", buildChannel)
    outputs.dir(generatedAppInfoDir)
    // 提交号来自 git/CI 环境，无法作为稳定输入，因此每次构建都重新生成。
    outputs.upToDateWhen { false }
    doLast {
        val commit =
            runCatching {
                envCommit.orNull
                    ?: envSha.orNull
                    ?: ProcessBuilder("git", "rev-parse", "--short=8", "HEAD")
                        .directory(rootProject.projectDir)
                        .redirectErrorStream(true)
                        .start()
                        .inputStream
                        .bufferedReader()
                        .use { it.readText() }
                        .trim()
            }.getOrNull().orEmpty().takeIf { it.isNotBlank() } ?: ""

        val file = generatedAppInfoDir.get().asFile.resolve("com/valhalla/core/AppBuildInfo.kt")
        file.parentFile.mkdirs()
        file.writeText(
            """
            // 由 :core:generateAppInfo 生成，请勿手改。
            package com.valhalla.core

            /**
             * 应用级元信息：版本号唯一来源是 gradle.properties；
             * 构建通道与提交号来自环境变量（VALHALLA_BUILD_CHANNEL / VALHALLA_BUILD_COMMIT / GITHUB_SHA），
             * 取不到提交号时 [BUILD_COMMIT] 为空字符串，界面显示「未知」，不编造。
             */
            object AppBuildInfo {
                const val APP_NAME: String = "Valhalla"
                const val VERSION_NAME: String = "$versionName"
                const val VERSION_CODE: Int = $versionCode
                const val BUILD_CHANNEL: String = "$buildChannel"
                const val BUILD_COMMIT: String = "$commit"
            }

            /** 一条真实依赖及其许可证与作用域（由构建脚本按依赖版本表生成）。 */
            data class AppDependency(
                val name: String,
                val version: String,
                val license: String,
                val scope: String,
            )

            /** 关于页展示的开源许可清单，只列实际声明的依赖。 */
            object AppDependencyInfo {
                val entries: List<AppDependency> =
                    listOf(
                        AppDependency("Kotlin", "$kotlinVersion", "Apache-2.0", "runtime"),
                        AppDependency("Kotlinx Coroutines", "$coroutinesVersion", "Apache-2.0", "runtime"),
                        AppDependency("Compose Multiplatform", "$composeVersion", "Apache-2.0", "runtime"),
                        AppDependency("AndroidX Activity Compose", "$activityComposeVersion", "Apache-2.0", "android"),
                        AppDependency("AndroidX Core", "$androidxCoreVersion", "Apache-2.0", "android"),
                        AppDependency("Kotlin Test", "$kotlinVersion", "Apache-2.0", "test"),
                        AppDependency("Kotlinx Coroutines Test", "$coroutinesVersion", "Apache-2.0", "test"),
                        AppDependency("AndroidX Test Ext JUnit", "$androidxTestJunitVersion", "Apache-2.0", "test"),
                        AppDependency("AndroidX Test Runner", "$androidxTestRunnerVersion", "Apache-2.0", "test"),
                        AppDependency("Android Gradle Plugin", "$agpVersion", "Apache-2.0", "build"),
                    )
            }

            """.trimIndent(),
        )
    }
}

kotlin.sourceSets
    .getByName("commonMain")
    .kotlin
    .srcDir(generateAppInfo)
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
    compileSdk =
        libs.versions.androidCompileSdk
            .get()
            .toInt()

    defaultConfig {
        minSdk =
            libs.versions.androidMinSdk
                .get()
                .toInt()
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
val jvmTestCompilation =
    kotlin.targets
        .getByName("jvm")
        .compilations
        .getByName("test")

tasks.register<JavaExec>("benchmarkExport") {
    group = "benchmark"
    description = "运行报文日志导出基准（5000 条 / 16 MiB），结果打印到控制台"
    dependsOn(tasks.named("jvmTestClasses"))
    classpath = jvmTestCompilation.output.allOutputs + jvmTestCompilation.runtimeDependencyFiles
    mainClass.set("com.valhalla.core.logging.PacketLogExportBenchmarkKt")
    jvmArgs("-Xmx1g")
}

// 只统计 JVM 单元测试覆盖率，不含 Android instrumented；行覆盖阈值 75%。
kover {
    reports {
        verify {
            rule {
                minBound(75)
            }
        }
    }
}
