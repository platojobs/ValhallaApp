# 隐私与日志脱敏说明（v1.9.0）

本文说明报文日志与导出中的脱敏规则。脱敏**默认开启、写入时执行、没有关闭入口**：
`InMemoryPacketLog.record` 先对帧载荷脱敏，再生成 HEX 与摘要，因此 TEXT / JSON / CSV
三种导出格式、复制到剪贴板和界面展示拿到的都是同一份已脱敏数据。

## 1. 脱敏规则表

| 命令 | 方向 | 字段 | 处理 | 保留内容 |
| --- | --- | --- | --- | --- |
| `AUTH` | TX | `mode` 之后的整段 `credential`（令牌 / 随机数 / 签名凭据） | 全部字节替换为 `*`（`0x2A`） | `mode`、`credentialLen` |
| `AUTH` | RX（成功） | `token` 值、`challenge` 值 | 全部字节替换为 `*` | `status`、`tokenLen`、`expiresIn`、`challengeLen` |
| `AUTH` | RX（失败） | 无敏感字段 | 原样保留 | 错误详情，便于排障 |
| `GET_DEVICE_INFO` | RX（成功） | `serialNumber` | 保留前 4 字节，其余替换为 `*` | `vendor`、`model`、`hardware`、`firmware` |
| 其他命令 | 任一 | 无 | 原样保留 | 完整载荷 |

不变式：

- **长度不变**：只替换字节值，帧长度、字段偏移与 CRC 仍可按原协议解析；
- **不可还原**：掩码字节不携带任何原文信息，导出文件无法恢复 credential/token/签名；
- **三格式一致**：脱敏发生在写入阶段，导出层不再做二次处理，因此 TEXT/JSON/CSV 一致；
- **非敏感命令不受影响**：`redactPayload` 在没有敏感字段时返回原数组，不产生额外分配。

实现位置：`core/src/commonMain/kotlin/com/valhalla/core/logging/PacketLogRedaction.kt`。

## 2. 设备标识

MAC / deviceId 不在协议 payload 中；若导出或界面需要展示，提供两种不可逆或半可读方案：

| 方法 | 输出示例 | 用途 |
| --- | --- | --- |
| `maskDeviceId` | `AA:B**********`（长度不变） | 需要人工辨认前缀的排障场景 |
| `hashDeviceId` | `dev#1a2b3c4d`（FNV-1a 32 位，稳定） | 需要区分设备但不应暴露原值的导出字段 |

设备信息应答中的 `serialNumber` 在写入报文日志时已截断为「前 4 字节 + 掩码」。

## 3. 本地存储与上传

脱敏只影响**报文日志与导出**。本地持久化仍包含设备档案、OTA 断点、鉴权令牌与偏好，
其位置与 key 见隐私页（`StorageInventory`）。当前版本没有网络上传能力（Android manifest
未声明 `INTERNET`），导出文件只通过系统保存/分享面板交给用户。

## 4. 验证与限制

自动化测试（JVM 宿主）覆盖：

- `PacketLogRedactionTest.authRequestCredentialIsMaskedWithSameLength`
- `PacketLogRedactionTest.authResponseTokenAndChallengeAreMaskedAndExpiryPreserved`
- `PacketLogRedactionTest.deviceSerialKeepsOnlyPrefix`
- `PacketLogRedactionTest.nonSensitiveCommandIsUntouched`
- `PacketLogRedactionTest.packetLogExportsAreRedactedInAllFormats`
- `PacketLogRedactionTest.deviceIdMaskingAndHashingAreStableAndIrreversible`

未验证：

- 设备日志（非报文日志）中由固件回传的自由文本是否含敏感信息，当前不解析、不脱敏；
- 真机系统分享面板、第三方应用读取导出文件的行为；
- 崩溃埋点上报链路（本轮未接入远端上报，不涉及脱敏验证）。
