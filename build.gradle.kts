plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.kover) apply false
}

// ---------------------------------------------------------------------------
// 静态分析配置集中在这里，避免每个模块重复；detekt 只分析真实源码目录，
// 不扫 build/ 生成代码。基线仅记录存量问题，新增问题仍会让任务失败。
// ---------------------------------------------------------------------------
subprojects {
    plugins.withId("io.gitlab.arturbosch.detekt") {
        extensions.configure<io.gitlab.arturbosch.detekt.extensions.DetektExtension> {
            source.setFrom(fileTree(projectDir) { include("src/**/*.kt") })
            config.setFrom(rootProject.files("config/detekt/detekt.yml"))
            baseline = file("detekt-baseline.xml")
            parallel = true
            buildUponDefaultConfig = true
        }
    }
    plugins.withId("org.jlleitschuh.gradle.ktlint") {
        extensions.configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
            filter {
                include { element -> element.file.path.contains("/src/") }
                exclude { element -> element.file.path.contains("/build/generated/") }
            }
        }
    }
}
