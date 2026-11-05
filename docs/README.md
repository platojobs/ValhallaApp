# 文档索引

ValhallaApp 的文档按「先看怎么交付、再看怎么实现」的顺序组织。仓库概览与快速开始见
[根目录 README](../README.md)。

## 交付与发布

| 文档 | 说明 |
| --- | --- |
| [delivery.md](delivery.md) | 交付物清单、模块说明、构建命令、产物路径、CI 入口、可交付 vs 未完成对照 |
| [release-notes/v2.2.0.md](release-notes/v2.2.0.md) | v2.2.0 跨端运行时验签、构建期公钥注入与异常桥接 |
| [release-notes/v2.0.0.md](release-notes/v2.0.0.md) | v2.0.0 版本要点、能力边界、已知限制、安装前提、真机验收清单 |
| [environment-report.md](environment-report.md) | 本机工具链版本与实际构建/测试记录 |
| [../CHANGELOG.md](../CHANGELOG.md) | v0.0.1 → v2.2.0 全量版本演进 |

## 架构与协议

| 文档 | 说明 |
| --- | --- |
| [architecture.md](architecture.md) | 分层与数据流、BLE 适配、调度器接入、OTA 时序、错误边界、并发与压力结论 |
| [protocol.md](protocol.md) | 帧格式、状态码、命令表、续传扩展、签名挑战应答 |
| [firmware.md](firmware.md) | 固件包格式、签名段、分块读取、预检与断点持久化 |
| [testing.md](testing.md) | 设备侧模拟器、场景矩阵、故障注入、端到端命令链 |

## 数据、隐私与权限

| 文档 | 说明 |
| --- | --- |
| [storage.md](storage.md) | 设备档案、OTA 历史、序列化格式、保留策略、隐私清单来源 |
| [export.md](export.md) | 报文日志导出：文件名、筛选、上限、TEXT/JSON/CSV 示例、脱敏与流式拼装 |
| [privacy.md](privacy.md) | 脱敏规则表、设备标识截断/哈希、本地存储与上传边界 |
| [permissions.md](permissions.md) | Android/iOS 权限声明、权限状态机、断连映射、自动重连、真机验收 |
| [security.md](security.md) | 威胁模型、签名鉴权、固件验签、令牌安全存储与未验证项 |

## 产品

| 文档 | 说明 |
| --- | --- |
| [product.md](product.md) | 面向使用者的功能一览、语言与主题、文案规范、关于/帮助/隐私约定 |

## 约定

- 所有文档描述**已实现行为**；未在真机验证的能力统一标注「未验证」；
- 版本号以 [gradle.properties](../gradle.properties) 为唯一来源；
- 新增文档时请在本索引登记，并确保相对链接可点击。
