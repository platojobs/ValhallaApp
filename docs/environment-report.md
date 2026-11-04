# 本机工具链检查记录

检查时间：2026-11-04（Asia/Shanghai），机器：macOS 27.2 / arm64。

| 工具 | 状态 | 版本 / 路径 | 说明 |
| --- | --- | --- | --- |
| JDK | 可用 | OpenJDK 25.0.4（默认）、Oracle JDK 21.0.6 | Gradle 统一使用 JDK 21 启动，保证 AGP/Kotlin 兼容 |
| Gradle | 本地缓存 | `~/.gradle/wrapper/dists/gradle-8.14.3-bin` | 系统无 `gradle` 命令，通过 wrapper 复用已缓存发行版 |
| Android SDK | 可用 | `/Users/platojobs/Library/Android/sdk` | platforms 31/33/34/35/36、build-tools 33~37 齐备 |
| Android 构建插件 | 可用 | AGP 8.13.1（本地缓存） | compileSdk 36 |
| Xcode | 可用 | Xcode 27.2 (Build 27B52) | iOS 壳工程与 Framework 集成使用 |
| Swift | 可用 | Swift 6.4.2 | iOS 入口 SwiftUI |
| Kotlin/Native | 未预装 | `~/.konan` 不存在 | iOS 编译需首次下载工具链，受网络与耗时影响 |
| 网络 | 部分可用 | Maven Central 200、Google Maven 可用、GitHub 可达 | `services.gradle.org` 直连超时，因此复用缓存发行版 |
| Git | 可用 | 2.54.0 | 提交作者统一 `platojobs <platojobs@gmail.com>` |

## 结论

- JVM 单元测试与 Android 编译在本机具备完整条件。
- iOS 目标已在 Gradle 中声明；能否完成 Kotlin/Native 工具链下载决定 iOS 编译验证结果，
  实际执行结果见仓库最终提交说明。
