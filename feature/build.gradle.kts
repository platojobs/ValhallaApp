import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.detekt)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.kover)
}

/**
 * 构建期固件验签公钥注入。
 *
 * 优先级：环境变量 `VALHALLA_FIRMWARE_PUBKEY` > Gradle property
 * `valhalla.firmwarePublicKey` > 缺省（不注入）。
 *
 * 注入后同时生成：
 * - `firmware/signing-key.pem` 资源（Android assets / JVM classpath / iOS test resources）；
 * - `BuildInjectedFirmwareKey` 常量（iOS 静态 framework 不打包资源时的兜底）。
 *
 * 仓库不包含任何真实公钥或私钥；未注入时生成空常量，运行时回退到 v2.1.0 的 `WARN` 策略。
 */
val firmwareKeyEnv = providers.environmentVariable("VALHALLA_FIRMWARE_PUBKEY")
val firmwareKeyProperty = providers.gradleProperty("valhalla.firmwarePublicKey")
val firmwareKeyOutputDir = layout.buildDirectory.dir("generated/firmware")
val firmwareKeyResourcesDir = firmwareKeyOutputDir.map { it.dir("resources") }
val firmwareKeyKotlinDir = firmwareKeyOutputDir.map { it.dir("kotlin") }

val generateFirmwareKey by tasks.registering {
    inputs.property("firmwareKeyFromEnvironment", firmwareKeyEnv.orElse(""))
    inputs.property("firmwareKeyFromGradleProperty", firmwareKeyProperty.orElse(""))
    outputs.dir(firmwareKeyOutputDir)

    doLast {
        val outputDir = firmwareKeyOutputDir.get().asFile
        outputDir.deleteRecursively()

        val envValue = firmwareKeyEnv.orNull?.takeIf { it.isNotBlank() }
        val propertyValue = firmwareKeyProperty.orNull?.takeIf { it.isNotBlank() }
        val selected = envValue ?: propertyValue
        val source =
            when {
                envValue != null -> "environment"
                propertyValue != null -> "gradle-property"
                else -> "none"
            }

        val normalized =
            selected
                ?.replace("\\n", "\n")
                ?.trim()
                ?.takeIf { it.isNotEmpty() }

        val resourcesDir = outputDir.resolve("resources/firmware").apply { mkdirs() }
        val kotlinDir = outputDir.resolve("kotlin/com/valhalla/feature/firmware").apply { mkdirs() }

        if (normalized != null) {
            resourcesDir.resolve("signing-key.pem").writeText(normalized + "\n")
        }

        val escaped =
            normalized
                ?.replace("\\", "\\\\")
                ?.replace("\"", "\\\"")
                ?.replace("\r", "")
                ?.replace("\n", "\\n")
                .orEmpty()

        kotlinDir.resolve("BuildInjectedFirmwareKey.kt").writeText(
            """
            package com.valhalla.feature.firmware

            /**
             * 由 Gradle 任务 `generateFirmwareKey` 生成，请勿手改。
             * 来源：$source；空字符串表示构建期未注入公钥。
             */
            internal object BuildInjectedFirmwareKey {
                const val PEM: String = "$escaped"
            }

            """.trimIndent(),
        )

        logger.lifecycle("Firmware signing key injection: source=$source, present=${normalized != null}")
    }
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

    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        getByName("commonMain").kotlin.srcDir(firmwareKeyKotlinDir)
        getByName("jvmMain").resources.srcDir(firmwareKeyResourcesDir)
        getByName("iosArm64Main").resources.srcDir(firmwareKeyResourcesDir)
        getByName("iosSimulatorArm64Main").resources.srcDir(firmwareKeyResourcesDir)
        commonMain.dependencies {
            implementation(project(":core"))
            implementation(project(":data"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
        }
        jvmTest.dependencies {
            implementation(project(":protocol-testkit"))
        }
    }
}

android {
    namespace = "com.valhalla.feature"
    compileSdk =
        libs.versions.androidCompileSdk
            .get()
            .toInt()

    sourceSets.getByName("main").assets.srcDir(firmwareKeyResourcesDir)

    defaultConfig {
        minSdk =
            libs.versions.androidMinSdk
                .get()
                .toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

tasks.matching { it.name.startsWith("compile") && it.name.contains("Kotlin") }.configureEach {
    dependsOn(generateFirmwareKey)
}
tasks
    .matching {
        it.name.endsWith("ProcessResources") ||
            (it.name.startsWith("merge") && it.name.endsWith("Assets"))
    }.configureEach {
        dependsOn(generateFirmwareKey)
    }

// ktlint 会读取 commonMain 源集（含生成的公钥常量），需与生成任务建立显式依赖。
tasks.matching { it.name.contains("ktlint", ignoreCase = true) }.configureEach {
    dependsOn(generateFirmwareKey)
}

// 只统计 JVM 单元测试覆盖率，不含 Android instrumented；行覆盖阈值 60%。
// Compose UI 包依赖 Android/iOS 渲染环境，JVM 单测无法执行，按可测逻辑范围统计
// （ui 包排除；其余逻辑仍按 60% 硬阈值校验，见 docs/static-analysis-baseline.md）。
kover {
    reports {
        filters {
            excludes {
                packages("com.valhalla.feature.ui")
            }
        }
        verify {
            rule {
                minBound(60)
            }
        }
    }
}
