# 安全模型与防护边界（v1.1.0）

本文档描述 App 侧在 v1.1.0 引入的三项安全能力：AUTH 签名挑战应答、固件签名校验、
会话令牌安全存储，并明确威胁模型、已覆盖的攻击面与**未覆盖/未验证**的边界。

相关实现：

- `core/security/Sha256.kt`、`core/security/HmacSha256.kt`
- `core/security/AuthSigner.kt`、`core/protocol/message/AuthMessages.kt`
- `core/firmware/FirmwareSignature.kt`、`core/firmware/FirmwarePrecheck.kt`
- `core/security/AuthToken.kt`、`TokenStore`
- `data/security/TokenStoreFactory.kt`（各平台 actual）

## 1. 威胁模型

### 1.1 假设对手能做的事

| 能力 | 说明 |
| --- | --- |
| 被动窃听 BLE 链路 | 可读取 App 与设备之间的明文帧 |
| 主动注入/篡改帧 | 可修改、重放、乱序、丢弃 App 或设备的帧 |
| 替换固件文件 | 可替换用户选择的固件文件、修改文件头/payload/签名段 |
| 读取普通存储 | 可读取应用私有的 SharedPreferences / 普通文件（未加密部分） |
| 逆向 App 二进制 | 可分析算法流程与硬编码常量 |

### 1.2 假设对手做不到的事

- 无法从 Android Keystore / iOS Keychain 中提取密钥明文（依赖平台安全硬件/系统隔离）。
- 无法获知预共享密钥（AUTH 密钥、固件签名密钥）；密钥不随固件或 App 包下发。
- 无法伪造平台安全存储的完整性（Keystore/Keychain 由系统校验）。

### 1.3 明确不在范围内

- 设备固件自身的漏洞、调试口/物理拆机、供应链投毒。
- BLE 配对的中间人攻击（当前协议未做链路层双向认证；签名鉴权可缓解会话劫持，
  但不能替代配对绑定）。
- 拒绝服务（攻击者持续丢包/断连只影响可用性，不在此模型内）。
- 已 root/jailbreak 设备上的本地提权与内存抓取。

## 2. 防护设计与边界

### 2.1 AUTH 签名挑战应答（HMAC-SHA256）

- 签名消息：`version || challenge || clientNonce || deviceDigest`，带长度前缀，避免
  拼接歧义；`challenge` 每次鉴权重新获取，用于抗重放。
- 实现为纯 Kotlin（`core/security`），不依赖平台加密 API，因此可在 `jvmTest` 中用
  RFC 4231 / FIPS 180-4 标准向量验证；比较使用常量时间实现，降低时序侧信道。
- **防护**：无密钥的攻击者无法伪造 `AUTH(SIGNED)`；篡改 challenge/nonce/deviceDigest
  任一项都会导致验签失败；签名绑定设备摘要，不能跨设备复用。
- **边界**：预共享密钥的注入与轮换不在本仓库实现；若密钥泄露，需重新配网/轮换。
  旧设备仍可使用 `TOKEN` / `CHALLENGE`，该回退路径的强度低于签名模式。

### 2.2 固件签名校验

- 签名段追加在 payload 之后，flags bit0 标记；旧固件（flags bit0=0）解析与 OTA
  行为不变。
- 签名对象：`SHA-256(文件头 104 字节 || payload)`；文件头包含 flags 与头部 CRC，
  篡改头字段同样会导致验签失败。
- 验签接口 `FirmwareSignatureVerifier` 与预检集成；预检在 CRC、型号、版本检查之外
  新增签名分支，失败原因写入 `FirmwarePrecheckResult.errors`。
- 策略：`REQUIRE` 拒绝未签名固件（生产推荐）；`WARN` 仅告警（默认，兼容旧固件）；
  `IGNORE` 完全忽略。**已签名但验签失败始终拒绝**，不受策略影响。
- **防护**：攻击者替换 payload 或签名段都会被检测；即使重算头部 CRC，也无法在没有
  密钥的情况下生成有效签名。
- **边界**：当前完整验签实现为 HMAC-SHA256 测试密钥；ECDSA-P256 仅定义了算法编号与
  验签接口，**尚未接入平台 ECDSA 实现**，也未做真机验证。生产环境必须替换
  `HmacSha256TestVerifier` 为基于 Android Keystore / iOS Security 的 ECDSA 验签器。

### 2.3 会话令牌安全存储

- 统一接口 `TokenStore`（`save` / `load` / `clear` / `loadValid(now)`），过期判断
  集中在 `AuthToken.isExpired`。
- Android：Android Keystore 生成 AES-256/GCM 密钥，密文（IV || ciphertext）以
  Base64 写入私有 SharedPreferences；密钥不出 Keystore。
- iOS：Keychain `kSecClassGenericPassword`，`kSecAttrAccessibleAfterFirstUnlock`。
- JVM：内存实现，仅用于测试/桌面调试，进程退出即丢失。
- 解密失败、数据损坏或令牌过期时一律按「无令牌」处理，并清除存储。
- **防护**：普通存储被读取时只能看到密文；系统回收密钥后旧密文不可解密。
- **边界**：Android 真机 Keystore 的密钥生成/解密、iOS 真机 Keychain 的读写与
  App 删除后重建行为**未验证**（本仓库只验证了编译与模拟器构建）。JVM 实现不是安全
  存储，不得用于生产桌面发行。

## 3. 向后兼容

| 场景 | 行为 |
| --- | --- |
| 旧设备/旧固件（仅 TOKEN/CHALLENGE） | 不变；签名鉴权为新增可选路径 |
| 旧固件文件（flags bit0=0） | 解析与 OTA 不变；预检按策略告警或通过 |
| 已签名固件 + 未配置验签器 | `REQUIRE` 拒绝，`WARN` 告警（不静默通过） |
| 令牌存储迁移 | 存储层损坏/旧格式一律视为无令牌，不阻塞启动 |

## 4. 未验证项与后续工作

1. **真机 Keystore/Keychain 行为**：未在 Android/iOS 真机验证密钥生成、加密解密、
   卸载重装、锁屏后的可访问性；仅通过编译与 iOS 模拟器链接。
2. **ECDSA-P256 验签**：算法编号与接口已预留，平台实现与密钥分发未实现。
3. **固件侧实现**：设备固件需按 `docs/protocol.md` 4.1.1 与
   `docs/firmware.md` 1.1 实现相同的规范化编码与算法；测试桩只证明协议一致性，
   不代表硬件行为。
4. **密钥管理**：预共享密钥的生产注入、轮换、撤销流程未在仓库内实现。
5. **链路层绑定**：BLE 配对绑定与 MITM 防护依赖系统/BLE 栈，未在此层实现。
