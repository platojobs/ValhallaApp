# 本机工具链检查与验证记录

检查时间：2026-11-04（Asia/Shanghai），机器：macOS 27.2 / arm64。

## 工具链

| 工具 | 状态 | 版本 / 路径 | 说明 |
| --- | --- | --- | --- |
| JDK | 可用 | OpenJDK 25.0.4（默认）、Oracle JDK 21.0.6 | Gradle 统一使用 JDK 21 启动，保证 AGP/Kotlin 兼容 |
| Gradle | 本地缓存 | `~/.gradle/wrapper/dists/gradle-8.14.3-bin` | 系统无 `gradle` 命令，通过 wrapper 复用已缓存发行版 |
| Android SDK | 可用 | `/Users/platojobs/Library/Android/sdk` | platforms 31/33/34/35/36、build-tools 33~37 齐备 |
| Android 构建插件 | 可用 | AGP 8.13.1（本地缓存） | compileSdk 36 |
| Xcode | 可用 | Xcode 27.2 (Build 27B52) | iOS 壳工程与 Framework 集成使用 |
| Swift | 可用 | Swift 6.4.2 | iOS 入口 SwiftUI |
| Kotlin/Native | 可用 | `~/.konan/kotlin-native-prebuilt-macos-aarch64-2.3.20` | 由 Gradle 在首次 iOS 编译时自动下载 |
| 网络 | 部分可用 | Maven Central、Google Maven、JetBrains 下载源可用 | `services.gradle.org` 直连超时，因此复用缓存发行版 |
| Git | 可用 | 2.54.0 | 提交作者统一 `platojobs <platojobs@gmail.com>` |

## 实际执行结果

| 验证项 | 命令 | 结果 |
| --- | --- | --- |
| core 单元测试 | `./gradlew :core:jvmTest` | 50 项全部通过 |
| data 单元测试 | `./gradlew :data:jvmTest` | 11 项全部通过（连接池 8 + 扫描过滤 3） |
| feature 单元测试 | `./gradlew :feature:jvmTest` | 13 项全部通过 |
| Android 源集 | `./gradlew :data:compileDebugKotlinAndroid` | 通过 |
| Android Debug APK | `./gradlew :composeApp:assembleDebug` | 通过，产物 `composeApp/build/outputs/apk/debug/composeApp-debug.apk`（约 11 MB） |
| iOS 模拟器 Framework | `./gradlew :composeApp:linkDebugFrameworkIosSimulatorArm64` | 通过 |
| iOS App 壳工程 | `xcodebuild ... ARCHS=arm64 ONLY_ACTIVE_ARCH=YES CODE_SIGNING_ALLOWED=NO build` | 通过（仅 Apple Silicon 模拟器架构，未做真机签名） |

## 环境冲突与处理

1. **Compose Multiplatform 版本调整**：初始选型 1.12.2，其 Android 产物（`androidx.compose.runtime:runtime-saveable-android:1.12.1`）
   在 AAR 元数据中要求 compileSdk 37 与 AGP 9.1.0+，而本机 AGP 为 8.13.1、compileSdk 36。为避免升级整条工具链，
   降级到 Compose Multiplatform 1.10.3，Android 构建恢复正常。
2. **移除 iosX64**：Compose Multiplatform 1.10.3 不提供 iosX64 产物，且项目面向 Apple Silicon 模拟器/真机，
   因此仅保留 `iosArm64` 与 `iosSimulatorArm64`。
3. **Xcode 高斯架构链接**：`generic/platform=iOS Simulator` 默认尝试 x86_64 + arm64 双架构，而 Kotlin 仅产出 arm64
   Framework。命令行验证时固定 `ARCHS=arm64`；Xcode 图形界面在 Apple Silicon 上默认只构建 arm64。
4. **Xcode 构建脚本的 JDK**：Xcode 环境优先携带 JDK 25，构建脚本会检测并切换到 JDK 21，否则 Gradle 直接失败。
5. **embedAndSignAppleFrameworkForXcode**：该任务在本机触发 `syncComposeResourcesForIos` 的 provider 缺失错误，
   改为在 Xcode 构建阶段按 SDK/配置调用 `linkDebugFramework*` / `linkReleaseFramework*`，
   并使用静态 Framework 直接链接，避免资源同步路径。
