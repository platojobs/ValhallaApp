package com.valhalla.feature.i18n

import androidx.compose.runtime.staticCompositionLocalOf

/** 单条文案的中英两个版本。 */
data class StringsEntry(
    val zh: String,
    val en: String,
)

/**
 * 应用文案表（唯一来源）。
 *
 * 采用 commonMain 纯 Kotlin 资源而不是平台 XML：语言切换需要立即重组生效，
 * 且 key 完整性、占位符一致性可以在 commonTest 中直接断言。
 */
object AppStrings {
    private val entries: Map<StringKey, StringsEntry> =
        mapOf(
            // ---- 通用 ----
            StringKey.APP_NAME to StringsEntry("Valhalla", "Valhalla"),
            StringKey.BACK to StringsEntry("返回", "Back"),
            StringKey.CANCEL to StringsEntry("取消", "Cancel"),
            StringKey.CONFIRM to StringsEntry("确认", "Confirm"),
            StringKey.RETRY to StringsEntry("重试", "Retry"),
            StringKey.REFRESH to StringsEntry("刷新", "Refresh"),
            StringKey.UNKNOWN to StringsEntry("未知", "Unknown"),
            StringKey.NOT_AVAILABLE to StringsEntry("不可用", "Not available"),
            StringKey.SAVED to StringsEntry("已保存", "Saved"),
            StringKey.SAVING to StringsEntry("保存中…", "Saving…"),
            StringKey.LOADING to StringsEntry("读取中…", "Loading…"),
            StringKey.COPY to StringsEntry("复制", "Copy"),
            StringKey.CLEAR to StringsEntry("清空", "Clear"),
            StringKey.SHARE to StringsEntry("分享", "Share"),
            StringKey.SAVE_FILE to StringsEntry("保存文件", "Save file"),
            StringKey.SETTINGS to StringsEntry("设置", "Settings"),
            StringKey.CLOSE to StringsEntry("关闭", "Close"),
            StringKey.DASH to StringsEntry("—", "—"),
            StringKey.ERROR_PREFIX to StringsEntry("错误：{detail}", "Error: {detail}"),
            StringKey.SAVE to StringsEntry("保存", "Save"),
            StringKey.TECHNICAL_DETAIL to
                StringsEntry(
                    "技术详情：{detail}",
                    "Technical details: {detail}",
                ),
            // ---- 扫描 ----
            StringKey.SCAN_TITLE to StringsEntry("设备扫描", "Device scan"),
            StringKey.SCAN_HINT to
                StringsEntry(
                    "确保设备已上电并处于广播模式。扫描需要蓝牙权限：Android 12 及以上使用「附近的设备」，旧版本使用定位权限；iOS 使用蓝牙授权。",
                    "Make sure the device is powered on and advertising. Scanning needs Bluetooth permission: " +
                        "Android 12+ uses Nearby devices, older versions use location; iOS uses Bluetooth authorization.",
                ),
            StringKey.START_SCAN to StringsEntry("开始扫描", "Start scan"),
            StringKey.STOP_SCAN to StringsEntry("停止", "Stop"),
            StringKey.SCANNING to StringsEntry("正在扫描附近的设备…", "Scanning for nearby devices…"),
            StringKey.SCAN_CONNECTING to StringsEntry("正在连接 {device}…", "Connecting to {device}…"),
            StringKey.UNNAMED_DEVICE to StringsEntry("未命名设备", "Unnamed device"),
            StringKey.DEVICE_RSSI to StringsEntry("{id} · 信号 {rssi} dBm", "{id} · RSSI {rssi} dBm"),
            StringKey.SCAN_EMPTY_SCANNING to StringsEntry("正在扫描附近的设备…", "Scanning for nearby devices…"),
            StringKey.SCAN_EMPTY_TIMEOUT to
                StringsEntry(
                    "扫描 12 秒仍无设备：请确认设备已上电、处于广播模式并靠近手机，然后重新扫描。",
                    "No device after 12 seconds: check that the device is powered on, advertising, and close to the phone, then scan again.",
                ),
            StringKey.SCAN_EMPTY_NO_DEVICES to
                StringsEntry(
                    "暂无设备：请确认设备已上电并处于广播模式，然后重新扫描。",
                    "No devices: check that the device is powered on and advertising, then scan again.",
                ),
            StringKey.SCAN_EMPTY_NO_PERMISSION to
                StringsEntry(
                    "没有蓝牙权限，无法发现设备：请在系统弹窗中授予权限后重试。",
                    "Bluetooth permission is missing, so no devices can be found: grant it in the system prompt and retry.",
                ),
            StringKey.SCAN_EMPTY_PERMISSION_PERMANENTLY_DENIED to
                StringsEntry(
                    "蓝牙权限已被永久拒绝：请前往系统设置开启，返回应用后再扫描。",
                    "Bluetooth permission is permanently denied: enable it in system settings, then return and scan.",
                ),
            StringKey.SCAN_EMPTY_BLUETOOTH_OFF to
                StringsEntry(
                    "蓝牙已关闭，无法扫描：请开启蓝牙后重试。",
                    "Bluetooth is off, so scanning is unavailable: turn Bluetooth on and retry.",
                ),
            StringKey.SCAN_EMPTY_BLUETOOTH_RESETTING to
                StringsEntry(
                    "蓝牙正在重置：请等待几秒后重试。",
                    "Bluetooth is resetting: wait a few seconds and retry.",
                ),
            StringKey.SCAN_EMPTY_UNSUPPORTED to
                StringsEntry(
                    "当前设备不支持蓝牙低功耗，无法扫描。",
                    "This device does not support Bluetooth Low Energy, so scanning is unavailable.",
                ),
            StringKey.SCAN_EMPTY_UNKNOWN to
                StringsEntry(
                    "暂时无法获取蓝牙状态：请稍后重试。",
                    "Bluetooth state is unavailable: retry shortly.",
                ),
            StringKey.SCAN_EMPTY_ERROR to
                StringsEntry(
                    "扫描失败：请检查蓝牙状态后重试。详情：{detail}",
                    "Scan failed: check Bluetooth state and retry. Details: {detail}",
                ),
            StringKey.BLUETOOTH_UNAVAILABLE to
                StringsEntry(
                    "蓝牙当前不可用：请检查权限与蓝牙开关后重试。",
                    "Bluetooth is unavailable: check permission and the Bluetooth switch, then retry.",
                ),
            // ---- 权限/蓝牙引导 ----
            StringKey.GUIDANCE_NEED_PERMISSION_TITLE to StringsEntry("需要蓝牙权限", "Bluetooth permission required"),
            StringKey.GUIDANCE_NEED_PERMISSION_MESSAGE to
                StringsEntry(
                    "扫描并连接设备需要蓝牙权限。Android 12 及以上使用「附近的设备」权限，旧版本使用定位权限。",
                    "Scanning and connecting need Bluetooth permission. Android 12+ uses Nearby devices; older versions use location.",
                ),
            StringKey.GUIDANCE_PERMISSION_DENIED_TITLE to StringsEntry("蓝牙权限被拒绝", "Bluetooth permission denied"),
            StringKey.GUIDANCE_PERMISSION_DENIED_MESSAGE to
                StringsEntry(
                    "没有蓝牙权限无法扫描设备。可再次授权；若系统不再弹窗，请前往系统设置手动开启。",
                    "Without Bluetooth permission no devices can be found. Grant it again, or enable it in system settings if the prompt no longer appears.",
                ),
            StringKey.GUIDANCE_PERMISSION_PERMANENT_TITLE to
                StringsEntry(
                    "蓝牙权限已被永久拒绝",
                    "Bluetooth permission permanently denied",
                ),
            StringKey.GUIDANCE_PERMISSION_PERMANENT_MESSAGE to
                StringsEntry(
                    "系统不会再弹出授权窗口。请前往「设置 → 应用 → Valhalla → 权限」开启蓝牙/附近设备权限。",
                    "The system will not show the prompt again. Open Settings → Apps → Valhalla → Permissions and enable Bluetooth/Nearby devices.",
                ),
            StringKey.GUIDANCE_BLUETOOTH_OFF_TITLE to StringsEntry("蓝牙已关闭", "Bluetooth is off"),
            StringKey.GUIDANCE_BLUETOOTH_OFF_MESSAGE to
                StringsEntry(
                    "请开启蓝牙后重试；开启后扫描与连接会自动恢复。",
                    "Turn Bluetooth on and retry; scanning and connections resume automatically.",
                ),
            StringKey.GUIDANCE_BLUETOOTH_RESETTING_TITLE to StringsEntry("蓝牙正在重置", "Bluetooth is resetting"),
            StringKey.GUIDANCE_BLUETOOTH_RESETTING_MESSAGE to
                StringsEntry(
                    "系统正在重启蓝牙服务，请稍候再试。",
                    "The system is restarting the Bluetooth service. Wait a moment and retry.",
                ),
            StringKey.GUIDANCE_UNSUPPORTED_TITLE to
                StringsEntry(
                    "设备不支持蓝牙低功耗",
                    "Bluetooth Low Energy unsupported",
                ),
            StringKey.GUIDANCE_UNSUPPORTED_MESSAGE to
                StringsEntry(
                    "当前设备没有可用的 BLE 适配器，无法扫描或连接 Valhalla 硬件。",
                    "This device has no usable BLE adapter, so Valhalla hardware cannot be scanned or connected.",
                ),
            StringKey.GUIDANCE_UNKNOWN_TITLE to StringsEntry("蓝牙状态未知", "Bluetooth state unknown"),
            StringKey.GUIDANCE_UNKNOWN_MESSAGE to
                StringsEntry(
                    "暂时无法获取蓝牙状态，请稍后重试。",
                    "Bluetooth state cannot be read right now. Retry shortly.",
                ),
            StringKey.ACTION_GRANT_PERMISSION to StringsEntry("授予权限", "Grant permission"),
            StringKey.ACTION_OPEN_SETTINGS to StringsEntry("去系统设置", "Open settings"),
            StringKey.ACTION_ENABLE_BLUETOOTH to StringsEntry("开启蓝牙", "Turn on Bluetooth"),
            StringKey.ACTION_RESCAN to StringsEntry("返回扫描", "Back to scan"),
            // ---- 断连 ----
            StringKey.DISCONNECT_USER_TITLE to StringsEntry("已断开连接", "Disconnected"),
            StringKey.DISCONNECT_USER_MESSAGE to
                StringsEntry(
                    "你主动断开了与设备的连接。可以返回扫描页重新连接。",
                    "You disconnected from the device. Return to the scan page to reconnect.",
                ),
            StringKey.DISCONNECT_PERMISSION_TITLE to StringsEntry("蓝牙权限丢失", "Bluetooth permission lost"),
            StringKey.DISCONNECT_PERMISSION_MESSAGE to
                StringsEntry(
                    "系统收回了蓝牙权限，无法继续与设备通信。请在设置中重新授权后连接。",
                    "The system revoked Bluetooth permission, so communication stopped. Re-authorize in settings and reconnect.",
                ),
            StringKey.DISCONNECT_BLUETOOTH_OFF_TITLE to StringsEntry("蓝牙已关闭", "Bluetooth is off"),
            StringKey.DISCONNECT_BLUETOOTH_OFF_MESSAGE to
                StringsEntry(
                    "蓝牙关闭导致链路中断。开启蓝牙后将自动尝试重连。",
                    "Bluetooth was turned off, which broke the link. Reconnection starts automatically after Bluetooth is on.",
                ),
            StringKey.DISCONNECT_RESETTING_TITLE to StringsEntry("蓝牙正在重置", "Bluetooth is resetting"),
            StringKey.DISCONNECT_RESETTING_MESSAGE to
                StringsEntry(
                    "系统正在重启蓝牙服务，链路暂时不可用，稍后会自动重连。",
                    "The system is restarting Bluetooth, so the link is temporarily unavailable. Reconnection follows automatically.",
                ),
            StringKey.DISCONNECT_TIMEOUT_TITLE to StringsEntry("连接超时", "Connection timed out"),
            StringKey.DISCONNECT_TIMEOUT_MESSAGE to
                StringsEntry(
                    "在限定时间内未能完成连接。请确认设备已上电并靠近手机后重试。",
                    "The connection did not complete in time. Confirm the device is powered on and nearby, then retry.",
                ),
            StringKey.DISCONNECT_DEVICE_TITLE to StringsEntry("设备已断开连接", "Device disconnected"),
            StringKey.DISCONNECT_DEVICE_MESSAGE to
                StringsEntry(
                    "设备侧断开了链路，可能已关机、超出范围或正在重启。靠近设备后可重新连接。",
                    "The device ended the link, possibly because it powered off, moved out of range, or restarted. Move closer and reconnect.",
                ),
            StringKey.DISCONNECT_CONNECT_FAILED_TITLE to StringsEntry("连接失败", "Connection failed"),
            StringKey.DISCONNECT_CONNECT_FAILED_MESSAGE to
                StringsEntry(
                    "与设备建立链路失败。请靠近设备、确认设备可连接后重试。",
                    "The link could not be established. Move closer, confirm the device is connectable, and retry.",
                ),
            StringKey.DISCONNECT_UNKNOWN_TITLE to StringsEntry("连接已断开", "Disconnected"),
            StringKey.DISCONNECT_UNKNOWN_MESSAGE to
                StringsEntry(
                    "未知原因导致链路中断。可尝试重新连接。",
                    "The link dropped for an unknown reason. Try reconnecting.",
                ),
            // ---- 重连 ----
            StringKey.RECONNECT_WAITING to
                StringsEntry(
                    "将在 {seconds} 秒后进行第 {attempt} 次自动重连",
                    "Automatic reconnect attempt {attempt} in {seconds}s",
                ),
            StringKey.RECONNECT_CONNECTING to
                StringsEntry(
                    "正在执行第 {attempt} 次自动重连…",
                    "Running automatic reconnect attempt {attempt}…",
                ),
            StringKey.RECONNECT_PAUSED_BLUETOOTH_OFF to
                StringsEntry(
                    "蓝牙已关闭，自动重连已暂停；开启蓝牙后继续。",
                    "Bluetooth is off, so automatic reconnect is paused. It resumes when Bluetooth is back on.",
                ),
            StringKey.RECONNECT_PAUSED to StringsEntry("自动重连已暂停。", "Automatic reconnect is paused."),
            StringKey.RECONNECT_EXHAUSTED to
                StringsEntry(
                    "自动重连已达上限（{attempt} 次）：请手动重试或返回扫描页。",
                    "Automatic reconnect reached its limit ({attempt} attempts): retry manually or return to scan.",
                ),
            StringKey.RECONNECT_CANCELLED to StringsEntry("已取消自动重连。", "Automatic reconnect cancelled."),
            StringKey.RECONNECT_SUCCEEDED to StringsEntry("重连成功。", "Reconnected."),
            StringKey.CANCEL_RECONNECT to StringsEntry("取消重连", "Cancel reconnect"),
            StringKey.RECONNECT_NOW to StringsEntry("重新连接", "Reconnect"),
            // ---- 设备详情 ----
            StringKey.DETAIL_TITLE to StringsEntry("设备详情", "Device details"),
            StringKey.STATE_LABEL to StringsEntry("状态：{state}", "State: {state}"),
            StringKey.FIRMWARE_LABEL to StringsEntry("固件版本：{version}", "Firmware: {version}"),
            StringKey.BATTERY_LABEL to StringsEntry("电量：{percent}", "Battery: {percent}"),
            StringKey.CHARGING to StringsEntry("（充电中）", " (charging)"),
            StringKey.MODEL_LINE to
                StringsEntry(
                    "型号：{vendor} {model} · 序列号 {serial}",
                    "Model: {vendor} {model} · S/N {serial}",
                ),
            StringKey.AUTHENTICATE to StringsEntry("鉴权", "Authenticate"),
            StringKey.AUTH_MODE_LABEL to StringsEntry("鉴权模式", "Auth mode"),
            StringKey.AUTH_MODE_TOKEN to StringsEntry("预共享密钥", "Pre-shared key"),
            StringKey.AUTH_MODE_SIGNED to StringsEntry("签名挑战", "Signed challenge"),
            StringKey.AUTH_CREDENTIAL_LABEL to StringsEntry("密钥 / 令牌", "Key / token"),
            StringKey.AUTH_SECRET_HINT to
                StringsEntry(
                    "直接以预共享密钥/令牌鉴权（TOKEN 模式）。",
                    "Authenticate with the pre-shared key or token (TOKEN mode).",
                ),
            StringKey.AUTH_SIGNED_HINT to
                StringsEntry(
                    "先取设备挑战，再用 HMAC-SHA256 对挑战、随机数与设备摘要签名（SIGNED 模式）。",
                    "Fetch the device challenge, then HMAC-SHA256 sign challenge, nonce and device digest (SIGNED mode).",
                ),
            StringKey.REFRESH_INFO to StringsEntry("刷新信息", "Refresh info"),
            StringKey.ENTER_OTA to StringsEntry("进入 OTA 升级", "Open OTA update"),
            StringKey.OPEN_LOGS to StringsEntry("报文日志（TX/RX）", "Packet logs (TX/RX)"),
            StringKey.DEVICE_LOGS to StringsEntry("设备日志", "Device logs"),
            StringKey.PROFILE_TITLE to StringsEntry("设备档案", "Device profile"),
            StringKey.PROFILE_LAST_CONNECTED to StringsEntry("最后连接：{time}", "Last connected: {time}"),
            StringKey.PROFILE_SUMMARY to
                StringsEntry(
                    "最近固件：{version} · 电量：{percent}",
                    "Last firmware: {version} · Battery: {percent}",
                ),
            StringKey.PROFILE_LAST_OTA to StringsEntry("最近 OTA：{outcome}", "Last OTA: {outcome}"),
            StringKey.PROFILE_OTA_RECORD to
                StringsEntry(
                    "{outcome} · 固件 {version} · 耗时 {duration} ms",
                    "{outcome} · firmware {version} · {duration} ms",
                ),
            StringKey.ERROR_CODE_LABEL to StringsEntry("错误码：{code}", "Error code: {code}"),
            StringKey.AUTH_FAILED to
                StringsEntry(
                    "鉴权失败：{detail}。请确认设备处于配对模式后重试。",
                    "Authentication failed: {detail}. Confirm the device is in pairing mode and retry.",
                ),
            StringKey.AUTH_CHALLENGE_FAILED to
                StringsEntry(
                    "获取鉴权挑战失败：{detail}。请重新连接设备后重试。",
                    "Failed to obtain the authentication challenge: {detail}. Reconnect the device and retry.",
                ),
            StringKey.INFO_REFRESH_FAILED to
                StringsEntry(
                    "设备信息读取失败：{detail}。请确认设备在线后刷新。",
                    "Failed to read device info: {detail}. Confirm the device is online and refresh.",
                ),
            StringKey.DEVICE_CONFIG_READ_FAILED to
                StringsEntry(
                    "读取配置失败：{detail}。请确认设备在线后刷新。",
                    "Failed to read the setting: {detail}. Confirm the device is online and refresh.",
                ),
            StringKey.DEVICE_CONFIG_WRITE_FAILED to
                StringsEntry(
                    "写入配置失败：{detail}。请确认设备在线后重试。",
                    "Failed to write the setting: {detail}. Confirm the device is online and retry.",
                ),
            StringKey.FAILURE_DEVICE_REJECTED to
                StringsEntry(
                    "设备拒绝请求（{status}）。请确认固件与设备匹配后重试。",
                    "The device rejected the request ({status}). Confirm the firmware matches the device and retry.",
                ),
            StringKey.FAILURE_STATUS to
                StringsEntry(
                    "状态码 {code}",
                    "status {code}",
                ),
            StringKey.FAILURE_STATUS_DETAIL to
                StringsEntry(
                    "状态码 {code}：{detail}",
                    "status {code}: {detail}",
                ),
            StringKey.FAILURE_TIMEOUT to
                StringsEntry(
                    "设备无响应（已重试 {attempts} 次）。请确认设备在附近且电量充足后重试。",
                    "The device did not respond ({attempts} attempts). Confirm it is nearby and charged, then retry.",
                ),
            StringKey.FAILURE_TRANSPORT to
                StringsEntry(
                    "传输中断：{detail}。请重新连接设备后重试。",
                    "Transport interrupted: {detail}. Reconnect the device and retry.",
                ),
            StringKey.FAILURE_INVALID_RESPONSE to
                StringsEntry(
                    "设备应答无法解析：{detail}。请重试或重新连接设备。",
                    "The device response could not be parsed: {detail}. Retry or reconnect the device.",
                ),
            StringKey.FAILURE_UNKNOWN to
                StringsEntry(
                    "未知错误：{detail}。请重试；若持续出现请导出日志反馈。",
                    "Unknown error: {detail}. Retry; if it persists, export the logs and report it.",
                ),
            // ---- 配置 ----
            StringKey.CONFIG_TITLE to StringsEntry("设备配置", "Device configuration"),
            StringKey.CONFIG_RELOAD to StringsEntry("重新读取", "Reload"),
            StringKey.CONFIG_HINT to
                StringsEntry(
                    "配置键能力按固件版本与型号判定；不支持的键已隐藏，避免下发后设备返回参数非法。",
                    "Available keys are determined by firmware and model; unsupported keys are hidden to avoid invalid-parameter replies.",
                ),
            StringKey.CONFIG_HIDDEN_UNKNOWN_TARGET to
                StringsEntry(
                    "已隐藏 {count} 项配置：固件版本或型号未知。请先刷新设备信息，再判定可用性。",
                    "{count} settings hidden: firmware or model is unknown. Refresh device info to determine availability.",
                ),
            StringKey.CONFIG_HIDDEN_UNSUPPORTED to
                StringsEntry(
                    "已隐藏 {count} 项配置：当前设备（固件 {firmware}，型号 {model}）不支持。",
                    "{count} settings hidden: unsupported on this device (firmware {firmware}, model {model}).",
                ),
            StringKey.REFRESH_DEVICE_INFO to StringsEntry("刷新设备信息", "Refresh device info"),
            StringKey.CONFIG_SAVED_FIELD to StringsEntry("已保存 {field}", "Saved {field}"),
            StringKey.CONFIG_UNKNOWN_KEY to
                StringsEntry(
                    "无法保存：未知配置项「{key}」。请刷新配置列表后重试。",
                    "Cannot save: unknown setting \"{key}\". Reload the list and retry.",
                ),
            StringKey.CONFIG_UNSUPPORTED_FIELD to
                StringsEntry(
                    "无法保存「{field}」：当前固件或型号不支持该配置项。请刷新设备信息后重试。",
                    "Cannot save \"{field}\": unsupported on this firmware or model. Refresh device info and retry.",
                ),
            StringKey.CONFIG_READ_ONLY_FIELD to
                StringsEntry(
                    "无法保存「{field}」：该配置项只读。",
                    "Cannot save \"{field}\": it is read-only.",
                ),
            StringKey.CONFIG_SAVING_BUSY to
                StringsEntry(
                    "请稍候：正在保存 {field}，完成后再提交。",
                    "Wait: {field} is being saved. Submit again when it finishes.",
                ),
            StringKey.CONFIG_NO_KEYS to
                StringsEntry(
                    "当前没有可读取的配置项：固件或型号未知。请刷新设备信息后重试。",
                    "No readable settings: firmware or model is unknown. Refresh device info and retry.",
                ),
            StringKey.CONFIG_READ_FAILED to
                StringsEntry(
                    "读取失败：{detail}。请刷新设备信息后重试。",
                    "Read failed: {detail}. Refresh device info and retry.",
                ),
            StringKey.CONFIG_LOAD_FAILED to
                StringsEntry(
                    "保存「{field}」失败：{detail}。请确认设备在线后重试。",
                    "Saving \"{field}\" failed: {detail}. Confirm the device is online and retry.",
                ),
            StringKey.CONFIG_FIELD_READ_ONLY to StringsEntry("只读", "Read-only"),
            StringKey.CONFIG_FIELD_EDITABLE to StringsEntry("可编辑", "Editable"),
            StringKey.CONFIG_FIELD_UNSAVED to StringsEntry("未保存", "Unsaved"),
            StringKey.CONFIG_FIELD_SAVED to StringsEntry("已保存", "Saved"),
            StringKey.CONFIG_FIELD_SAVING to StringsEntry("保存中…", "Saving…"),
            StringKey.CONFIG_FIELD_LOADING to StringsEntry("读取中…", "Loading…"),
            // ---- OTA ----
            StringKey.OTA_TITLE to StringsEntry("固件升级（OTA）", "Firmware update (OTA)"),
            StringKey.OTA_DEVICE_LINE to
                StringsEntry(
                    "设备版本：{version} · 型号：{model}",
                    "Device firmware: {version} · Model: {model}",
                ),
            StringKey.SELECT_FIRMWARE to StringsEntry("选择固件", "Select firmware"),
            StringKey.RESELECT_FIRMWARE to StringsEntry("重新选择固件", "Select a different firmware"),
            StringKey.OPENING_PICKER to StringsEntry("正在打开文件选择器…", "Opening the file picker…"),
            StringKey.PRECHECK_PROGRESS to
                StringsEntry(
                    "正在预检固件… {percent}%",
                    "Prechecking firmware… {percent}%",
                ),
            StringKey.CANCEL_PRECHECK to StringsEntry("取消预检", "Cancel precheck"),
            StringKey.OTA_IDLE to StringsEntry("等待开始", "Waiting to start"),
            StringKey.OTA_PREPARING to
                StringsEntry(
                    "准备中：{bytes} 字节，CRC={crc}{session}",
                    "Preparing: {bytes} bytes, CRC={crc}{session}",
                ),
            StringKey.OTA_TRANSFERRING to
                StringsEntry(
                    "{stage}：已发送 {sent}/{total} 字节（{percent}%）{resume}",
                    "{stage}: {sent}/{total} bytes sent ({percent}%){resume}",
                ),
            StringKey.OTA_RESUME_NOTE to StringsEntry("（从 {offset} 续传）", " (resumed from {offset})"),
            StringKey.OTA_RETRANSMISSIONS to
                StringsEntry(
                    "分片重传 {count} 次",
                    "Chunk retransmissions: {count}",
                ),
            StringKey.OTA_VERIFYING to
                StringsEntry(
                    "校验中：设备正在核对 CRC={crc}",
                    "Verifying: the device is checking CRC={crc}",
                ),
            StringKey.OTA_SUCCEEDED to
                StringsEntry(
                    "升级完成：{chunks} 片，CRC={crc}",
                    "Update complete: {chunks} chunks, CRC={crc}",
                ),
            StringKey.OTA_REBOOT_REQUIRED to StringsEntry("，设备将重启", ", the device will restart"),
            StringKey.OTA_ABORTED to
                StringsEntry(
                    "升级已中止：{reason}（已发送 {sent}/{total} 字节）",
                    "Update aborted: {reason} ({sent}/{total} bytes sent)",
                ),
            StringKey.OTA_PAUSED_SAFE_POINT to
                StringsEntry(
                    "队列已暂停，当前设备停在安全点（已发送 {sent}/{total} 字节），断点已保留。",
                    "Queue paused at a safe point ({sent}/{total} bytes sent); the checkpoint is kept.",
                ),
            StringKey.OTA_FAILED to
                StringsEntry(
                    "升级失败：{reason}。请按原因处理后重试。",
                    "Update failed: {reason}. Resolve the cause and retry.",
                ),
            StringKey.OTA_SENT_BYTES to
                StringsEntry(
                    "已发送 {sent}/{total} 字节",
                    "{sent}/{total} bytes sent",
                ),
            StringKey.OTA_RESUME_SAVED to
                StringsEntry(
                    "已保存断点：{sent}/{total} 字节（会话 {session}）",
                    "Checkpoint saved: {sent}/{total} bytes (session {session})",
                ),
            StringKey.OTA_SESSION_SUFFIX to
                StringsEntry(
                    "，会话 {id}",
                    ", session {id}",
                ),
            StringKey.START_OTA to StringsEntry("开始升级", "Start update"),
            StringKey.RESUME_OTA to StringsEntry("继续上次升级", "Resume last update"),
            StringKey.RESUME_OTA_WITH_PROGRESS to
                StringsEntry(
                    "继续上次升级（{sent}/{total}）",
                    "Resume last update ({sent}/{total})",
                ),
            StringKey.CLEAR_RESUME to StringsEntry("清除断点", "Clear checkpoint"),
            StringKey.ABORT_OTA to StringsEntry("中止升级", "Abort update"),
            StringKey.RESET_STATE to StringsEntry("重置状态", "Reset state"),
            StringKey.FIRMWARE_VERSION_LINE to
                StringsEntry(
                    "版本 {version} · payload {bytes}",
                    "Version {version} · payload {bytes}",
                ),
            StringKey.FIRMWARE_CRC32_LINE to
                StringsEntry(
                    "CRC32 声明 {declared} · 实际 {computed}",
                    "CRC32 declared {declared} · computed {computed}",
                ),
            StringKey.FIRMWARE_CRC16_LINE to StringsEntry("CRC16 {value}", "CRC16 {value}"),
            StringKey.FIRMWARE_RESUME_READY to
                StringsEntry(
                    "可续传：从 {offset} 继续（已传输 {sent}/{total} 字节）",
                    "Resumable from {offset} ({sent}/{total} bytes transferred)",
                ),
            StringKey.FIRMWARE_NOT_PRECHECKED to StringsEntry("尚未预检", "Not prechecked yet"),
            StringKey.FIRMWARE_PRECHECK_PASSED to
                StringsEntry(
                    "预检通过，可以开始升级。",
                    "Precheck passed; the update can start.",
                ),
            StringKey.FIRMWARE_PRECHECK_FAILED to
                StringsEntry(
                    "预检未通过，OTA 不可启动。请修正固件后重试。",
                    "Precheck failed, so OTA cannot start. Fix the firmware and retry.",
                ),
            StringKey.FIRMWARE_LOAD_FAILED to
                StringsEntry(
                    "固件加载失败：{detail}。请重新选择固件文件。",
                    "Firmware load failed: {detail}. Select the firmware file again.",
                ),
            StringKey.FIRMWARE_FILE_READ_FAILED to
                StringsEntry(
                    "读取固件文件失败：{detail}。请确认文件仍可访问后重新选择。",
                    "Failed to read the firmware file: {detail}. Confirm the file is still accessible and select it again.",
                ),
            StringKey.RESUME_INVALID to
                StringsEntry(
                    "本地断点已失效，已改为从头升级。",
                    "The local resume checkpoint is invalid, so the update restarts from the beginning.",
                ),
            StringKey.RESUME_MISMATCH to
                StringsEntry(
                    "断点与当前固件不匹配（版本/总长/CRC32），已改为从头升级。",
                    "The checkpoint does not match this firmware (version, total size, or CRC32), so the update restarts from the beginning.",
                ),
            StringKey.RESUME_PRECHECK_BLOCKED to
                StringsEntry(
                    "当前固件预检未通过，暂不能续传。请重新预检或从头升级。",
                    "Precheck did not pass for this firmware, so resuming is unavailable. Re-run the precheck or start over.",
                ),
            StringKey.RESUME_CRC16_MISSING to
                StringsEntry(
                    "无法计算固件 CRC16，已禁用续传。请重新预检或从头升级。",
                    "The firmware CRC16 could not be computed, so resuming is disabled. Re-run the precheck or start over.",
                ),
            StringKey.RESUME_CRC16_MISMATCH to
                StringsEntry(
                    "固件内容与断点记录的 CRC16 不一致，已禁用续传。请从头升级。",
                    "The firmware CRC16 differs from the checkpoint record, so resuming is disabled. Start over.",
                ),
            StringKey.RECHECK_FIRMWARE to StringsEntry("重新预检", "Re-run precheck"),
            StringKey.OTA_STAGE_PREPARING to StringsEntry("准备中", "Preparing"),
            StringKey.OTA_STAGE_TRANSFERRING to StringsEntry("传输中", "Transferring"),
            StringKey.OTA_STAGE_VERIFYING to StringsEntry("校验中", "Verifying"),
            StringKey.OTA_STAGE_APPLYING to StringsEntry("应用固件", "Applying firmware"),
            StringKey.OTA_STAGE_COMPLETED to StringsEntry("已完成", "Completed"),
            StringKey.OTA_OUTCOME_SUCCEEDED to StringsEntry("成功", "Succeeded"),
            StringKey.OTA_OUTCOME_FAILED to StringsEntry("失败", "Failed"),
            StringKey.OTA_OUTCOME_ABORTED to StringsEntry("中止", "Aborted"),
            StringKey.OTA_REASON_CRC_MISMATCH to
                StringsEntry(
                    "设备回报 CRC 不一致。请重新选择固件并重新预检。",
                    "The device reported a CRC mismatch. Select the firmware again and re-run the precheck.",
                ),
            StringKey.OTA_REASON_DISCONNECTED to
                StringsEntry(
                    "链路已断开。请重新连接设备后使用「继续上次升级」。",
                    "The link is down. Reconnect the device, then use \"Resume last update\".",
                ),
            StringKey.OTA_REASON_SESSION_EXISTS to
                StringsEntry(
                    "已有升级会话在进行。请先中止或等待其结束后再试。",
                    "Another update session is already running. Abort it or wait for it to finish.",
                ),
            StringKey.OTA_REASON_EMPTY_FIRMWARE to
                StringsEntry(
                    "固件内容为空。请重新选择有效的固件文件。",
                    "The firmware is empty. Select a valid firmware file.",
                ),
            StringKey.OTA_REASON_INVALID_LENGTH to
                StringsEntry(
                    "固件长度非法（{length}）。请重新选择有效的固件文件。",
                    "Invalid firmware length ({length}). Select a valid firmware file.",
                ),
            StringKey.OTA_REASON_FIRMWARE_READ to
                StringsEntry(
                    "固件读取失败（offset={offset}, length={length}）。请重新选择固件文件。",
                    "Firmware read failed (offset={offset}, length={length}). Select the firmware file again.",
                ),
            StringKey.OTA_REASON_READ_FIRMWARE to
                StringsEntry(
                    "读取固件失败（offset={offset}, length={length}）。请重新选择固件文件。",
                    "Reading the firmware failed (offset={offset}, length={length}). Select the firmware file again.",
                ),
            StringKey.OTA_REASON_TIMEOUT to
                StringsEntry(
                    "设备无响应（尝试 {attempts} 次后超时）。请确认设备在附近且电量充足后重试。",
                    "The device did not respond (timed out after {attempts} attempts). Confirm it is nearby and charged, then retry.",
                ),
            StringKey.OTA_REASON_TRANSPORT to
                StringsEntry(
                    "链路传输失败（尝试 {attempts} 次）。请重新连接设备后重试。",
                    "Link transfer failed ({attempts} attempts). Reconnect the device and retry.",
                ),
            StringKey.OTA_REASON_OFFSET_TOO_LARGE to
                StringsEntry(
                    "设备确认的续传偏移超出范围（{offset} ≥ {total}）。请清除断点后从头升级。",
                    "The device accepted an out-of-range resume offset ({offset} ≥ {total}). Clear the checkpoint and start over.",
                ),
            StringKey.OTA_REASON_OFFSET_UNEXPECTED to
                StringsEntry(
                    "未请求续传，但设备返回偏移 {offset}。请清除断点或重置状态后重试。",
                    "Resume was not requested, but the device returned offset {offset}. Clear the checkpoint or reset and retry.",
                ),
            StringKey.OTA_REASON_OFFSET_GREATER_THAN_REQUESTED to
                StringsEntry(
                    "设备确认偏移 {accepted} 大于请求偏移 {requested}。请清除断点后从头升级。",
                    "The device accepted offset {accepted}, which exceeds the requested {requested}. Clear the checkpoint and start over.",
                ),
            StringKey.OTA_REASON_CANCELLED to
                StringsEntry(
                    "升级已取消。可重新开始或继续上次升级。",
                    "The update was cancelled. Start again or resume the last update.",
                ),
            StringKey.OTA_ABORT_TIMEOUT to
                StringsEntry(
                    "设备超时，升级已中止。请确认设备在附近且电量充足后重试。",
                    "The device timed out, so the update was aborted. Confirm it is nearby and charged, then retry.",
                ),
            StringKey.OTA_ABORT_VERIFY_FAILED to
                StringsEntry(
                    "设备校验失败，升级已中止。请重新选择固件并重试。",
                    "The device failed verification, so the update was aborted. Select the firmware again and retry.",
                ),
            StringKey.OTA_ABORT_TRANSPORT to
                StringsEntry(
                    "链路异常，升级已中止。请重新连接设备后继续升级。",
                    "The link failed, so the update was aborted. Reconnect the device and resume.",
                ),
            StringKey.OTA_ABORT_DEVICE_REJECTED to
                StringsEntry(
                    "设备拒绝升级，已中止。请确认固件与设备型号和版本匹配。",
                    "The device rejected the update, so it was aborted. Confirm the firmware matches the model and version.",
                ),
            StringKey.OTA_ABORT_USER to
                StringsEntry(
                    "你取消了升级。可随时重新开始或继续上次升级。",
                    "You cancelled the update. Start again or resume at any time.",
                ),
            StringKey.OTA_REASON_DEVICE_REJECTED to
                StringsEntry(
                    "设备拒绝升级请求：{detail}。请确认固件与设备匹配后重试。",
                    "The device rejected the update request: {detail}. Confirm the firmware matches the device and retry.",
                ),
            StringKey.OTA_REASON_INVALID_RESPONSE to
                StringsEntry(
                    "设备应答无法解析：{detail}。请重试；若持续出现，请记录日志反馈。",
                    "The device response could not be parsed: {detail}. Retry; if it persists, export logs and report it.",
                ),
            StringKey.OTA_FALLBACK_NOT_ACCEPTED to
                StringsEntry(
                    "设备未接受续传偏移，已从 0 开始。",
                    "The device did not accept the resume offset, so the transfer restarted from 0.",
                ),
            StringKey.OTA_FALLBACK_LOWERED to
                StringsEntry(
                    "设备将续传偏移下调到 {accepted} 字节（请求 {requested} 字节）。",
                    "The device lowered the resume offset to {accepted} bytes (requested {requested}).",
                ),
            StringKey.OTA_FALLBACK_REJECTED to
                StringsEntry(
                    "设备拒绝续传（{detail}），已回退为从头开始。",
                    "The device rejected the resume ({detail}); the transfer restarted from the beginning.",
                ),
            // ---- 日志与导出 ----
            StringKey.LOGS_TITLE to StringsEntry("报文日志", "Packet logs"),
            StringKey.LOGS_SEARCH to
                StringsEntry(
                    "搜索命令名 / 摘要 / HEX / seq",
                    "Search command / summary / HEX / seq",
                ),
            StringKey.LOGS_ALL_COMMANDS to StringsEntry("全部命令", "All commands"),
            StringKey.LOGS_ERRORS_ONLY to StringsEntry("仅错误", "Errors only"),
            StringKey.LOGS_START_TIME to StringsEntry("起始时间", "Start time"),
            StringKey.LOGS_END_TIME to StringsEntry("结束时间", "End time"),
            StringKey.LOGS_TIME_PLACEHOLDER to StringsEntry("YYYY-MM-DD HH:mm:ss", "YYYY-MM-DD HH:mm:ss"),
            StringKey.LOGS_TIME_HINT to
                StringsEntry(
                    "留空表示不限，含边界",
                    "Leave empty for no bound; bounds are inclusive",
                ),
            StringKey.LOGS_TIME_RANGE_ERROR to
                StringsEntry(
                    "起始时间不能晚于结束时间。",
                    "Start time cannot be later than end time.",
                ),
            StringKey.LOGS_TIME_FORMAT_ERROR to
                StringsEntry(
                    "时间格式无效：应为 YYYY-MM-DD HH:mm:ss。",
                    "Invalid time format: use YYYY-MM-DD HH:mm:ss.",
                ),
            StringKey.LOGS_ENTRY_LIMIT to StringsEntry("条数上限", "Entry limit"),
            StringKey.LOGS_BYTES_LIMIT to StringsEntry("大小上限（KB）", "Size limit (KB)"),
            StringKey.LOGS_ENTRY_RANGE to
                StringsEntry(
                    "允许 {min}..{max}",
                    "Allowed {min}..{max}",
                ),
            StringKey.LOGS_BYTES_RANGE to StringsEntry("允许 1..{max}", "Allowed 1..{max}"),
            StringKey.LOGS_ESTIMATE_LOADING to StringsEntry("正在估算导出量…", "Estimating export size…"),
            StringKey.LOGS_ESTIMATE_INVALID_RANGE to
                StringsEntry(
                    "时间范围无效：{detail}",
                    "Invalid time range: {detail}",
                ),
            StringKey.LOGS_ESTIMATE_INVALID_LIMIT to
                StringsEntry(
                    "上限输入无效：{detail}",
                    "Invalid limit: {detail}",
                ),
            StringKey.LOGS_ESTIMATE_NO_MATCH to
                StringsEntry(
                    "没有匹配的报文，不会导出空文件。",
                    "No matching packets, so no empty file will be exported.",
                ),
            StringKey.LOGS_ESTIMATE_TRUNCATED to
                StringsEntry(
                    "预计导出 {entries} 条 / {bytes}（匹配 {matched} 条，超出上限将截断最旧的一端）",
                    "Exporting about {entries} entries / {bytes} ({matched} matched; oldest entries are truncated when over the limit)",
                ),
            StringKey.LOGS_ESTIMATE to
                StringsEntry(
                    "预计导出 {entries} 条 / {bytes}",
                    "Exporting about {entries} entries / {bytes}",
                ),
            StringKey.LOGS_EXPORTING to
                StringsEntry(
                    "导出中 · {phase} {processed}/{total}",
                    "Exporting · {phase} {processed}/{total}",
                ),
            StringKey.LOGS_EXPORT_PHASE_FILTERING to StringsEntry("筛选", "Filtering"),
            StringKey.LOGS_EXPORT_PHASE_RENDERING to StringsEntry("生成", "Rendering"),
            StringKey.LOGS_EXPORT_PHASE_ASSEMBLING to StringsEntry("拼装", "Assembling"),
            StringKey.LOGS_EXPORT_PREPARING to StringsEntry("准备", "Preparing"),
            StringKey.LOGS_DISPLAY_COUNT to
                StringsEntry(
                    "显示 {shown} / {total} 条 · 上限 {capacity} 条，超出滚动淘汰",
                    "Showing {shown} / {total} · capacity {capacity}, oldest entries are evicted",
                ),
            StringKey.LOGS_EMPTY to
                StringsEntry(
                    "暂无报文：连接设备并触发鉴权、信息读取或 OTA 后，这里会记录 TX/RX 报文。",
                    "No packets yet: connect a device and authenticate, read info, or run OTA to record TX/RX packets.",
                ),
            StringKey.LOGS_NO_MATCH to
                StringsEntry(
                    "没有匹配当前筛选条件的报文。",
                    "No packets match the current filters.",
                ),
            StringKey.LOGS_SAVE_SUCCESS to
                StringsEntry(
                    "已保存 {name}（{entries} 条，{bytes} 字节）",
                    "Saved {name} ({entries} entries, {bytes} bytes)",
                ),
            StringKey.LOGS_SHARE_SUCCESS to
                StringsEntry(
                    "已分享 {name}（{entries} 条，{bytes} 字节）",
                    "Shared {name} ({entries} entries, {bytes} bytes)",
                ),
            StringKey.LOGS_SAVE_CANCELLED to
                StringsEntry(
                    "已取消保存，未写入文件。",
                    "Save cancelled; no file was written.",
                ),
            StringKey.LOGS_EXPORT_CANCELLED to
                StringsEntry(
                    "已取消导出，未写入文件。",
                    "Export cancelled; no file was written.",
                ),
            StringKey.LOGS_SAVE_UNAVAILABLE to
                StringsEntry(
                    "当前平台不支持保存为文件，可改用分享。",
                    "This platform cannot save files; use Share instead.",
                ),
            StringKey.LOGS_SHARE_UNAVAILABLE to
                StringsEntry(
                    "当前平台不支持系统分享。",
                    "System sharing is unavailable on this platform.",
                ),
            StringKey.LOGS_SAVE_FAILED to
                StringsEntry(
                    "保存失败：{detail}。请检查存储空间后重试。",
                    "Save failed: {detail}. Check free storage and retry.",
                ),
            StringKey.LOGS_SHARE_FAILED to
                StringsEntry(
                    "分享失败：{detail}。请重试或改用保存文件。",
                    "Share failed: {detail}. Retry or save the file instead.",
                ),
            StringKey.LOGS_INVALID_LIMIT to
                StringsEntry(
                    "上限输入非法：请按提示的范围填写。",
                    "Invalid limit: use the range shown in the hint.",
                ),
            StringKey.LOGS_COPY_ENTRY to StringsEntry("复制", "Copy"),
            StringKey.LOGS_HEX to StringsEntry("HEX {hex}", "HEX {hex}"),
            StringKey.LOGS_PHASE_FILTERING to StringsEntry("筛选", "Filtering"),
            StringKey.LOGS_PHASE_RENDERING to StringsEntry("生成", "Rendering"),
            StringKey.LOGS_PHASE_ASSEMBLING to StringsEntry("拼装", "Assembling"),
            // ---- 单位/时间 ----
            StringKey.UNIT_BYTES to StringsEntry("{value} 字节", "{value} bytes"),
            StringKey.UNIT_KIB to StringsEntry("{value} KiB（{bytes} 字节）", "{value} KiB ({bytes} bytes)"),
            StringKey.UNIT_MIB to StringsEntry("{value} MiB（{bytes} 字节）", "{value} MiB ({bytes} bytes)"),
            StringKey.TIME_JUST_NOW to StringsEntry("刚刚", "just now"),
            StringKey.TIME_AGO_SUFFIX to StringsEntry("前", " ago"),
            StringKey.TIME_FUTURE_PREFIX to StringsEntry("未来 ", "in "),
            StringKey.TIME_FORMAT_PLACEHOLDER to
                StringsEntry(
                    "YYYY-MM-DD HH:mm:ss",
                    "YYYY-MM-DD HH:mm:ss",
                ),
            // ---- 设置 ----
            StringKey.SETTINGS_TITLE to StringsEntry("设置", "Settings"),
            StringKey.LANGUAGE_SECTION to StringsEntry("语言", "Language"),
            StringKey.LANGUAGE_SYSTEM to StringsEntry("跟随系统", "System"),
            StringKey.LANGUAGE_CHINESE to StringsEntry("中文", "中文"),
            StringKey.LANGUAGE_ENGLISH to StringsEntry("English", "English"),
            StringKey.THEME_SECTION to StringsEntry("主题", "Theme"),
            StringKey.THEME_SYSTEM to StringsEntry("跟随系统", "System"),
            StringKey.THEME_LIGHT to StringsEntry("浅色", "Light"),
            StringKey.THEME_DARK to StringsEntry("深色", "Dark"),
            StringKey.ABOUT_ENTRY to StringsEntry("关于", "About"),
            StringKey.HELP_ENTRY to StringsEntry("帮助与常见问题", "Help & FAQ"),
            StringKey.PRIVACY_ENTRY to StringsEntry("隐私说明", "Privacy"),
            // ---- 关于 ----
            StringKey.ABOUT_TITLE to StringsEntry("关于", "About"),
            StringKey.ABOUT_VERSION to StringsEntry("版本 {version}（{code}）", "Version {version} ({code})"),
            StringKey.ABOUT_BUILD to StringsEntry("构建通道：{channel}", "Build channel: {channel}"),
            StringKey.ABOUT_BUILD_INFO to
                StringsEntry(
                    "构建信息：Kotlin Multiplatform · Compose Multiplatform · {platform}",
                    "Build: Kotlin Multiplatform · Compose Multiplatform · {platform}",
                ),
            StringKey.ABOUT_PLATFORM to StringsEntry("运行平台：{platform}", "Platform: {platform}"),
            StringKey.ABOUT_FIRMWARE_KEY_CONFIGURED to
                StringsEntry(
                    "固件验签公钥：已配置",
                    "Firmware signing key: configured",
                ),
            StringKey.ABOUT_FIRMWARE_KEY_MISSING to
                StringsEntry(
                    "固件验签公钥：未配置（固件升级仅告警，不阻断）",
                    "Firmware signing key: not configured (updates warn only)",
                ),
            StringKey.ABOUT_LICENSE_TITLE to StringsEntry("开源许可", "Open-source licenses"),
            StringKey.ABOUT_LICENSE_BODY to
                StringsEntry(
                    "本应用使用 Kotlin、Kotlinx Coroutines、Compose Multiplatform（Apache License 2.0）以及平台蓝牙框架。完整许可见各依赖仓库的 LICENSE 文件。",
                    "This app uses Kotlin, Kotlinx Coroutines, Compose Multiplatform (Apache License 2.0), and platform Bluetooth frameworks. " +
                        "See each dependency's LICENSE file for full terms.",
                ),
            // ---- 帮助 ----
            StringKey.HELP_TITLE to StringsEntry("帮助与常见问题", "Help & FAQ"),
            StringKey.HELP_Q_PERMISSION to
                StringsEntry(
                    "为什么扫描不到设备 / 提示没有权限？",
                    "Why can't I scan, or why does it say permission is missing?",
                ),
            StringKey.HELP_A_PERMISSION to
                StringsEntry(
                    "扫描需要蓝牙权限。Android 12 及以上请授予「附近的设备」权限，Android 11 及以下请授予定位权限；iOS 需允许应用使用蓝牙。若曾拒绝，请到系统设置中为本应用开启权限，返回应用后重试。",
                    "Scanning needs Bluetooth permission. On Android 12+ grant Nearby devices; on Android 11 and below grant location. " +
                        "On iOS allow Bluetooth access. If you denied it, enable it in system settings for this app, come back, and retry.",
                ),
            StringKey.HELP_Q_CONNECT to StringsEntry("连接失败或频繁断开怎么办？", "Connection fails or keeps dropping?"),
            StringKey.HELP_A_CONNECT to
                StringsEntry(
                    "先确认设备已上电、处于广播模式并靠近手机，蓝牙已开启。连接超时或设备侧断开时，应用会自动按退避间隔重连（最多 5 次）；可随时取消并手动重试。蓝牙关闭期间重连会暂停，开启后继续。",
                    "Confirm the device is powered on, advertising, close to the phone, and Bluetooth is on. " +
                        "On timeout or device-side disconnect the app retries with backoff (up to 5 attempts); " +
                        "you can cancel and retry manually. Retries pause while Bluetooth is off and resume afterwards.",
                ),
            StringKey.HELP_Q_OTA to StringsEntry("OTA 升级失败怎么办？", "What if an OTA update fails?"),
            StringKey.HELP_A_OTA to
                StringsEntry(
                    "先看失败原因：CRC 不一致或固件不匹配时请重新选择正确固件并重新预检；链路断开时重新连接后可用「继续上次升级」从断点续传。断点保存在本机，成功或主动清除后删除。升级期间请保持设备靠近并避免关闭蓝牙。",
                    "Read the failure reason first. " +
                        "On a CRC mismatch or model mismatch, select the correct firmware and re-run the precheck. " +
                        "If the link dropped, reconnect and use \"Resume last update\" to continue from the checkpoint. " +
                        "Checkpoints stay on this device and are removed after success or when cleared. " +
                        "Keep the device nearby and leave Bluetooth on during the update.",
                ),
            StringKey.HELP_Q_LOGS to StringsEntry("日志保存在哪里？如何导出？", "Where are logs stored and how do I export them?"),
            StringKey.HELP_A_LOGS to
                StringsEntry(
                    "报文日志只保存在内存中（默认 500 条，滚动淘汰），不会自动落盘或上传。可在设备详情页进入「报文日志」，按命令、错误、时间范围与关键字筛选，再选择保存文件或系统分享。应用退出后内存日志会清空，需要留存请及时导出。",
                    "Packet logs live in memory only (500 entries by default, oldest evicted) and are never written to disk or uploaded automatically. " +
                        "Open Packet logs from device details, filter by command, error, time range, and keyword, then save a file or share it. " +
                        "In-memory logs are cleared when the app exits, so export them before leaving.",
                ),
            StringKey.HELP_Q_DEVICES to StringsEntry("最多可以连接几台设备？", "How many devices can I connect?"),
            StringKey.HELP_A_DEVICES to
                StringsEntry(
                    "默认最多同时连接 4 台。达到上限会直接提示，需要先断开一台；切换后详情、配置与 OTA 只作用于当前设备。",
                    "Up to 4 devices can be connected at once. At the limit you must disconnect one first; " +
                        "switching only changes which device details, configuration, and OTA apply to.",
                ),
            StringKey.HELP_Q_QUICK to StringsEntry("快捷操作会立即生效吗？", "Do quick actions take effect immediately?"),
            StringKey.HELP_A_QUICK to
                StringsEntry(
                    "重启与恢复出厂都需要二次确认，恢复出厂不可撤销；心跳只读取运行时长与电量。恢复出厂的确认令牌语义需固件确认。",
                    "Reboot and factory reset require confirmation, and factory reset cannot be undone; heartbeat only reads uptime and battery. " +
                        "Confirm-token semantics must be confirmed with the firmware.",
                ),
            // ---- 隐私 ----
            StringKey.PRIVACY_TITLE to StringsEntry("隐私说明", "Privacy"),
            StringKey.PRIVACY_STORAGE_TITLE to StringsEntry("本地存储内容", "Data stored on this device"),
            StringKey.PRIVACY_ITEM_PROFILES to
                StringsEntry(
                    "设备档案：最近连接时间、型号、固件版本、电量与 OTA 结果。",
                    "Device profiles: last connection time, model, firmware, battery, and OTA outcomes.",
                ),
            StringKey.PRIVACY_ITEM_OTA to
                StringsEntry(
                    "OTA 断点：续传偏移、会话与固件标识，用于断点续传。",
                    "OTA checkpoints: resume offset, session, and firmware identity used for resuming.",
                ),
            StringKey.PRIVACY_ITEM_TOKEN to
                StringsEntry(
                    "会话令牌：鉴权成功后保存；Android 使用 Keystore 加密，iOS 使用 Keychain。",
                    "Session token: stored after authentication; encrypted with Keystore on Android and Keychain on iOS.",
                ),
            StringKey.PRIVACY_ITEM_LOGS to
                StringsEntry(
                    "报文日志：仅内存环形缓冲，不落盘；退出应用即清空。",
                    "Packet logs: memory-only ring buffer, never written to disk; cleared when the app exits.",
                ),
            StringKey.PRIVACY_ITEM_SETTINGS to
                StringsEntry(
                    "语言与主题偏好：仅记录选择结果。",
                    "Language and theme preferences: only the selected values.",
                ),
            StringKey.PRIVACY_ITEM_PERMISSION_FLAG to
                StringsEntry(
                    "权限请求标记：记录是否已请求过 Bluetooth 权限（Android）。",
                    "Permission flag: whether Bluetooth permission has been requested (Android).",
                ),
            StringKey.PRIVACY_ITEM_FIRMWARE_LIBRARY to
                StringsEntry(
                    "固件库：文件名、版本、渠道、CRC-32 与原始文件引用；不缓存固件二进制，文件失效时需重新选择。",
                    "Firmware library: file name, version, channel, CRC-32, and the original file reference; " +
                        "firmware binaries are not cached and missing files must be re-selected.",
                ),
            StringKey.PRIVACY_ITEM_EVENTS to
                StringsEntry(
                    "设备事件时间线：每台设备最近 100 条连接、断开、鉴权、配置、OTA 与错误事件（已脱敏）。",
                    "Device event timeline: the latest 100 connection, disconnect, auth, config, OTA, and error events per device (redacted).",
                ),
            StringKey.PRIVACY_ITEM_METRICS to
                StringsEntry(
                    "设备指标采样：每台设备最近 50 个电量与 RSSI 数值点（本机采样，不含设备历史）。",
                    "Device metric samples: the latest 50 battery and RSSI values per device (local samples, not device history).",
                ),
            StringKey.PRIVACY_LOCATION_LABEL to StringsEntry("存储位置：", "Location: "),
            StringKey.PRIVACY_KEYS_LABEL to StringsEntry("键名：", "Keys: "),
            StringKey.PRIVACY_NOT_PERSISTED to
                StringsEntry(
                    "仅内存，退出即丢失",
                    "Memory only; lost on exit",
                ),
            StringKey.PRIVACY_NETWORK_TITLE to StringsEntry("是否上传数据", "Uploads"),
            StringKey.PRIVACY_NETWORK_BODY to
                StringsEntry(
                    "当前版本没有网络上传功能：应用不包含联网权限，设备档案、断点、令牌与日志都不会离开本机。",
                    "This version has no network upload: the app requests no internet permission, " +
                        "and profiles, checkpoints, tokens, and logs never leave this device.",
                ),
            StringKey.PRIVACY_DELETE_TITLE to StringsEntry("如何清除", "How to erase"),
            StringKey.PRIVACY_DELETE_BODY to
                StringsEntry(
                    "在系统设置中清除应用数据或卸载应用即可删除上述本地数据。报文日志退出应用后自动清空；OTA 断点可在升级页手动清除。",
                    "Clear the app's data in system settings or uninstall the app to remove the local data above. " +
                        "Packet logs clear when the app exits; OTA checkpoints can be cleared on the update page.",
                ),
            StringKey.PRIVACY_JVM_NOTE to
                StringsEntry(
                    "当前运行在测试宿主：所有数据仅存在于内存。",
                    "Running on the test host: all data exists in memory only.",
                ),
            // ---- 多设备管理（v2.3.0） ----
            StringKey.DEVICES_TITLE to StringsEntry("已连接设备", "Connected devices"),
            StringKey.DEVICES_ENTRY to StringsEntry("已连接设备（{count}）", "Connected devices ({count})"),
            StringKey.DEVICES_EMPTY to
                StringsEntry(
                    "当前没有已连接设备，请返回扫描页连接。",
                    "No connected devices. Return to the scan page to connect.",
                ),
            StringKey.DEVICES_SLOT to StringsEntry("槽位 {slot}/{capacity}", "Slot {slot}/{capacity}"),
            StringKey.DEVICES_CONNECTED_AT to StringsEntry("连接于 {time}", "Connected at {time}"),
            StringKey.DEVICES_ACTIVE to StringsEntry("当前操作", "Active"),
            StringKey.DEVICES_SWITCH to StringsEntry("切换", "Switch"),
            StringKey.DEVICES_DISCONNECT to StringsEntry("断开", "Disconnect"),
            StringKey.DEVICES_DISCONNECT_ALL to StringsEntry("全部断开", "Disconnect all"),
            StringKey.DEVICES_LIMIT_REACHED to
                StringsEntry(
                    "已达连接上限（{capacity} 台），请先断开一台再连接新设备。",
                    "Connection limit reached ({capacity}). Disconnect one device before connecting another.",
                ),
            StringKey.DEVICES_LIMIT_POLICY to
                StringsEntry(
                    "达到上限时直接拒绝新连接，不会静默排队，也不会自动断开已有设备。",
                    "At the limit, new connections are rejected rather than silently queued, and existing devices are never dropped automatically.",
                ),
            StringKey.DEVICES_BACK_TO_SCAN to StringsEntry("返回扫描", "Back to scan"),
            // ---- 扫描增强（v2.3.0） ----
            StringKey.SCAN_SORT_LABEL to StringsEntry("排序", "Sort"),
            StringKey.SCAN_SORT_SIGNAL to StringsEntry("信号强度", "Signal"),
            StringKey.SCAN_SORT_NAME to StringsEntry("名称", "Name"),
            StringKey.SCAN_SORT_LAST_CONNECTED to StringsEntry("最近连接", "Last connected"),
            StringKey.SCAN_RSSI_STRONG to StringsEntry("信号强", "Strong signal"),
            StringKey.SCAN_RSSI_MEDIUM to StringsEntry("信号中", "Medium signal"),
            StringKey.SCAN_RSSI_WEAK to StringsEntry("信号弱", "Weak signal"),
            StringKey.SCAN_PROFILE_MARK to StringsEntry("已建档", "Saved"),
            StringKey.SCAN_LAST_CONNECTED to StringsEntry("最近连接 {time}", "Last connected {time}"),
            StringKey.SCAN_NEVER_CONNECTED to StringsEntry("尚未连接过", "Never connected"),
            // ---- 快捷操作（v2.3.0） ----
            StringKey.QUICK_ACTIONS_TITLE to StringsEntry("快捷操作", "Quick actions"),
            StringKey.QUICK_REBOOT to StringsEntry("重启设备", "Reboot device"),
            StringKey.QUICK_FACTORY_RESET to StringsEntry("恢复出厂设置", "Factory reset"),
            StringKey.QUICK_HEARTBEAT to StringsEntry("心跳探测", "Heartbeat"),
            StringKey.QUICK_ACTION_FAILED to
                StringsEntry(
                    "操作失败：{detail}。请重试或重新连接设备。",
                    "Action failed: {detail}. Please retry or reconnect the device.",
                ),
            StringKey.QUICK_RUNNING to StringsEntry("操作进行中…", "Action in progress…"),
            StringKey.QUICK_REBOOT_CONFIRM_TITLE to StringsEntry("确认重启", "Confirm reboot"),
            StringKey.QUICK_REBOOT_CONFIRM_MESSAGE to
                StringsEntry(
                    "设备将重启并短暂断开连接，期间无法执行其它操作。",
                    "The device will reboot and disconnect briefly; other actions are unavailable during this time.",
                ),
            StringKey.QUICK_REBOOT_DELAY_LABEL to StringsEntry("延迟（毫秒）", "Delay (ms)"),
            StringKey.QUICK_FACTORY_CONFIRM_TITLE to StringsEntry("确认恢复出厂", "Confirm factory reset"),
            StringKey.QUICK_FACTORY_CONFIRM_MESSAGE to
                StringsEntry(
                    "设备将清除全部配置与用户数据。",
                    "The device will erase all configuration and user data.",
                ),
            StringKey.QUICK_FACTORY_IRREVERSIBLE to
                StringsEntry(
                    "此操作不可撤销，请确认已完成必要备份。",
                    "This action cannot be undone. Make sure any required backup is complete.",
                ),
            StringKey.QUICK_FIRMWARE_CONFIRM to
                StringsEntry(
                    "确认令牌语义需固件确认；当前协议只约定为字符串字段。",
                    "The confirm-token semantics must be confirmed with the firmware; the protocol only defines it as a string.",
                ),
            StringKey.QUICK_CONFIRM_TOKEN_LABEL to StringsEntry("确认令牌（可选）", "Confirm token (optional)"),
            StringKey.QUICK_REBOOT_SUCCESS to
                StringsEntry(
                    "重启指令已被设备接受（延迟 {delay} ms）。",
                    "Reboot accepted by the device (delay {delay} ms).",
                ),
            StringKey.QUICK_FACTORY_SUCCESS to
                StringsEntry(
                    "恢复出厂指令已被设备接受。",
                    "Factory reset accepted by the device.",
                ),
            StringKey.QUICK_HEARTBEAT_SUCCESS to
                StringsEntry(
                    "心跳正常：运行 {uptime}，电量 {percent}%。",
                    "Heartbeat OK: uptime {uptime}, battery {percent}%.",
                ),
            // ---- OTA 历史（v2.3.0） ----
            StringKey.OTA_HISTORY_TITLE to StringsEntry("OTA 历史", "OTA history"),
            StringKey.OTA_HISTORY_EMPTY to
                StringsEntry(
                    "尚未产生 OTA 记录。完成一次升级后会在此显示。",
                    "No OTA records yet. Records appear here after an update.",
                ),
            StringKey.OTA_HISTORY_PRIVACY to
                StringsEntry(
                    "仅保存在本机，不上传；最多保留最近 10 条。",
                    "Stored locally only, never uploaded; the latest 10 records are kept.",
                ),
            StringKey.OTA_HISTORY_EXPAND to StringsEntry("展开详情", "Expand details"),
            StringKey.OTA_HISTORY_COLLAPSE to StringsEntry("收起详情", "Collapse details"),
            StringKey.OTA_HISTORY_BYTES to StringsEntry("已发送 {sent}/{total} 字节", "Sent {sent}/{total} bytes"),
            StringKey.OTA_HISTORY_TIME to StringsEntry("时间 {time}", "Time {time}"),
            // ---- 交互细节（v2.3.0） ----
            StringKey.ACTION_RETRY to StringsEntry("重试", "Retry"),
            StringKey.COPIED to StringsEntry("已复制", "Copied"),
            StringKey.LOADING_DETAIL to StringsEntry("正在读取设备信息…", "Loading device info…"),
            StringKey.LONG_PRESS_TO_COPY to StringsEntry("长按可复制", "Long press to copy"),
            // ---- 设备参数面板（v2.4.0） ----
            StringKey.PARAM_TITLE to StringsEntry("设备参数", "Device parameters"),
            StringKey.PARAM_RELOAD to StringsEntry("重新读取", "Reload"),
            StringKey.PARAM_HINT to
                StringsEntry(
                    "参数值会在本地校验类型、范围与字节长度，校验失败不会下发。",
                    "Values are validated locally for type, range, and byte length; invalid input is never sent.",
                ),
            StringKey.PARAM_FIRMWARE_UNCONFIRMED to
                StringsEntry(
                    "需固件确认",
                    "Needs firmware confirmation",
                ),
            StringKey.PARAM_FIRMWARE_NOTE to
                StringsEntry(
                    "参数表来自协议约定值，固件尚未逐项确认；读写结果以设备应答为准，设备拒绝时展示错误码。",
                    "The parameter table comes from protocol conventions and is not yet confirmed with the firmware. " +
                        "Results are whatever the device returns, and rejections show the error code.",
                ),
            StringKey.PARAM_ID_LINE to StringsEntry("参数 ID：0x{id}", "Parameter ID: 0x{id}"),
            StringKey.PARAM_TYPE_INTEGER to StringsEntry("整数", "Integer"),
            StringKey.PARAM_TYPE_BOOLEAN to StringsEntry("布尔", "Boolean"),
            StringKey.PARAM_TYPE_ENUM to StringsEntry("枚举", "Enum"),
            StringKey.PARAM_TYPE_STRING to StringsEntry("字符串", "String"),
            StringKey.PARAM_RANGE to StringsEntry("取值 {range}", "Range {range}"),
            StringKey.PARAM_READ_ONLY to StringsEntry("只读", "Read-only"),
            StringKey.PARAM_READ_ONLY_REASON to
                StringsEntry(
                    "只读参数，设备不允许写入。",
                    "Read-only parameter; the device does not allow writes.",
                ),
            StringKey.PARAM_SAVE to StringsEntry("写入", "Write"),
            StringKey.PARAM_SAVING to StringsEntry("写入中…", "Writing…"),
            StringKey.PARAM_LOADING to StringsEntry("读取中…", "Reading…"),
            StringKey.PARAM_VALUE_LABEL to StringsEntry("当前值", "Current value"),
            StringKey.PARAM_UNSAVED to StringsEntry("未写入", "Unsaved"),
            StringKey.PARAM_SAVED_FIELD to StringsEntry("已写入 {name}", "{name} written"),
            StringKey.PARAM_NO_ITEMS to StringsEntry("当前参数表为空。", "The parameter table is empty."),
            StringKey.PARAM_UNKNOWN_ITEM to StringsEntry("未知参数：{id}", "Unknown parameter: {id}"),
            StringKey.PARAM_READ_ONLY_ITEM to StringsEntry("{name} 为只读参数", "{name} is read-only"),
            StringKey.PARAM_SAVING_BUSY to
                StringsEntry(
                    "正在写入 {name}，请稍候。",
                    "Writing {name}, please wait.",
                ),
            StringKey.PARAM_BUSY to
                StringsEntry(
                    "正在读取参数，请稍候。",
                    "Reading parameters, please wait.",
                ),
            StringKey.PARAM_LOAD_FAILED to
                StringsEntry(
                    "读取参数失败：{detail}，请重试。",
                    "Failed to read parameters: {detail}. Please retry.",
                ),
            StringKey.PARAM_SAVE_FAILED to
                StringsEntry(
                    "写入 {name} 失败：{detail}，请重试。",
                    "Failed to write {name}: {detail}. Please retry.",
                ),
            StringKey.PARAM_LOAD_ERROR to StringsEntry("读取失败", "Read failed"),
            // ---- 连接质量看板（v2.4.0） ----
            StringKey.DIAG_ENTRY to StringsEntry("诊断", "Diagnostics"),
            StringKey.DIAG_TITLE to StringsEntry("连接质量", "Connection quality"),
            StringKey.DIAG_HINT to
                StringsEntry(
                    "统计只基于本机真实报文日志；样本不足时不展示成功率与时延结论。",
                    "Statistics use only real on-device packet logs; with too few samples, success rate and latency conclusions are hidden.",
                ),
            StringKey.DIAG_SAMPLE_INSUFFICIENT to
                StringsEntry(
                    "样本不足（{count} 条）",
                    "Not enough samples ({count} requests)",
                ),
            StringKey.DIAG_SAMPLE_NOTE to
                StringsEntry(
                    "至少 {min} 条请求才展示完整统计。",
                    "At least {min} requests are needed for full statistics.",
                ),
            StringKey.DIAG_REQUESTS to StringsEntry("请求 {count} 条", "{count} requests"),
            StringKey.DIAG_SUCCESS_RATE to StringsEntry("成功率 {rate}%", "Success rate {rate}%"),
            StringKey.DIAG_LATENCY_AVG to
                StringsEntry(
                    "平均时延 {value} ms",
                    "Average latency {value} ms",
                ),
            StringKey.DIAG_LATENCY_MAX to StringsEntry("最大时延 {value} ms", "Max latency {value} ms"),
            StringKey.DIAG_RETRANSMISSIONS to StringsEntry("重传 {count} 次", "Retransmissions {count}"),
            StringKey.DIAG_OUT_OF_ORDER to
                StringsEntry(
                    "乱序应答 {count} 次",
                    "Out-of-order responses {count}",
                ),
            StringKey.DIAG_NO_RESPONSE to StringsEntry("无应答 {count} 条", "No response {count}"),
            StringKey.DIAG_ERROR_TITLE to StringsEntry("错误码分布", "Error codes"),
            StringKey.DIAG_ERROR_CODE_LINE to
                StringsEntry(
                    "{code} {title} ×{count}",
                    "{code} {title} ×{count}",
                ),
            StringKey.DIAG_COMMANDS_TITLE to StringsEntry("分命令明细", "By command"),
            StringKey.DIAG_COMMAND_LINE to
                StringsEntry(
                    "{command}：请求 {requests} / 成功 {successes} / 失败 {failures}",
                    "{command}: {requests} requests / {successes} OK / {failures} failed",
                ),
            StringKey.DIAG_COMMAND_LATENCY to
                StringsEntry(
                    "平均 {avg} ms · 最大 {max} ms",
                    "Avg {avg} ms · max {max} ms",
                ),
            StringKey.DIAG_RECENT_FAILURES to StringsEntry("最近失败", "Recent failures"),
            StringKey.DIAG_FAILURE_LINE to
                StringsEntry(
                    "{time} · {command} · {detail}",
                    "{time} · {command} · {detail}",
                ),
            StringKey.DIAG_UNKNOWN_ERROR to StringsEntry("未识别错误", "Unrecognized error"),
            StringKey.DIAG_EMPTY to
                StringsEntry(
                    "还没有报文日志，先连接设备并刷新信息。",
                    "No packet logs yet. Connect a device and refresh first.",
                ),
            StringKey.DIAG_NO_FAILURE to StringsEntry("暂无失败记录。", "No failures recorded."),
            StringKey.DIAG_MILLIS to StringsEntry("{value} ms", "{value} ms"),
            StringKey.DIAG_PERCENT to StringsEntry("{value}%", "{value}%"),
            // ---- 首次使用引导（v2.4.0） ----
            StringKey.ONBOARDING_TITLE to StringsEntry("开始使用", "Getting started"),
            StringKey.ONBOARDING_STEP to StringsEntry("第 {index}/{total} 步", "Step {index} of {total}"),
            StringKey.ONBOARDING_STEP_PERMISSION_TITLE to
                StringsEntry(
                    "权限与蓝牙",
                    "Permissions & Bluetooth",
                ),
            StringKey.ONBOARDING_STEP_PERMISSION_BODY to
                StringsEntry(
                    "扫描需要蓝牙权限：Android 12+ 为「附近的设备」，Android 11 及以下为定位权限；iOS 需允许蓝牙。蓝牙关闭时请先在系统设置中开启。本步骤只做说明，点击按钮才会触发系统权限请求。",
                    "Scanning needs Bluetooth permission: Nearby devices on Android 12+, location on Android 11 and below, and Bluetooth access on iOS. " +
                        "Turn Bluetooth on in system settings if it is off. " +
                        "This step only explains; the system prompt appears only after you tap the button.",
                ),
            StringKey.ONBOARDING_STEP_SCAN_TITLE to StringsEntry("扫描设备", "Scan for devices"),
            StringKey.ONBOARDING_STEP_SCAN_BODY to
                StringsEntry(
                    "点击「开始扫描」，设备会按信号强度或名称排序；已建档设备会显示最近连接时间。",
                    "Tap Start scan. Devices are sorted by signal or name; saved devices show their last connection time.",
                ),
            StringKey.ONBOARDING_STEP_CONNECT_TITLE to
                StringsEntry(
                    "连接与鉴权",
                    "Connect & authenticate",
                ),
            StringKey.ONBOARDING_STEP_CONNECT_BODY to
                StringsEntry(
                    "选择设备后建立连接；预共享密钥模式需要输入密钥，签名模式由设备发起挑战。鉴权成功后即可读取信息、配置与参数。",
                    "Pick a device to connect. Pre-shared-key mode asks for the key; signed mode uses a device challenge. " +
                        "After authentication you can read info, configuration, and parameters.",
                ),
            StringKey.ONBOARDING_GRANT_PERMISSION to
                StringsEntry(
                    "检查并授予权限",
                    "Check and grant permission",
                ),
            StringKey.ONBOARDING_SKIP to StringsEntry("跳过", "Skip"),
            StringKey.ONBOARDING_NEXT to StringsEntry("下一步", "Next"),
            StringKey.ONBOARDING_FINISH to StringsEntry("进入应用", "Enter app"),
            StringKey.ONBOARDING_REVIEW_ENTRY to StringsEntry("查看使用引导", "View getting started"),
            StringKey.ONBOARDING_REVIEW_NOTE to
                StringsEntry(
                    "引导只做说明，不会重复弹出系统权限框。",
                    "The guide only explains steps and never re-triggers the system permission prompt.",
                ),
            StringKey.ONBOARDING_NO_PERMISSION_PROMPT to
                StringsEntry(
                    "不会自动弹窗",
                    "No automatic permission prompt",
                ),
            // ---- 设置扩展（v2.4.0） ----
            StringKey.SETTINGS_RECONNECT_SECTION to StringsEntry("重连策略", "Reconnect policy"),
            StringKey.SETTINGS_RECONNECT_ATTEMPTS to StringsEntry("最大重连次数", "Max attempts"),
            StringKey.SETTINGS_RECONNECT_DELAY to
                StringsEntry(
                    "退避上限（秒）",
                    "Backoff cap (seconds)",
                ),
            StringKey.SETTINGS_LOG_CAPACITY to StringsEntry("日志容量", "Log capacity"),
            StringKey.SETTINGS_LOG_CAPACITY_NOTE to
                StringsEntry(
                    "变更对新建连接立即生效，已有连接保持当前容量；超出后丢弃最旧条目。",
                    "Changes apply to new connections immediately; existing connections keep their capacity. Oldest entries are dropped when full.",
                ),
            StringKey.SETTINGS_EXPORT_SECTION to StringsEntry("导出默认值", "Export defaults"),
            StringKey.SETTINGS_EXPORT_ENTRIES to StringsEntry("默认条数", "Default entries"),
            StringKey.SETTINGS_EXPORT_BYTES to StringsEntry("默认大小（KiB）", "Default size (KiB)"),
            StringKey.SETTINGS_EXPORT_HEX to
                StringsEntry(
                    "单条 HEX 上限（字符）",
                    "Max HEX per entry (chars)",
                ),
            StringKey.SETTINGS_EXPORT_NOTE to
                StringsEntry(
                    "导出页仍可临时修改这些值。",
                    "The export page can still override these values.",
                ),
            StringKey.SETTINGS_RANGE_HINT to StringsEntry("范围 {min}..{max}", "Range {min}..{max}"),
            StringKey.SETTINGS_INVALID_VALUE to
                StringsEntry(
                    "请输入 {min}..{max} 之间的整数",
                    "Enter an integer between {min} and {max}",
                ),
            StringKey.SETTINGS_APPLY to StringsEntry("应用", "Apply"),
            StringKey.SETTINGS_RESET_DEFAULTS to StringsEntry("恢复默认", "Reset to defaults"),
            StringKey.SETTINGS_APPLIED to StringsEntry("已保存设置", "Settings saved"),
            StringKey.SETTINGS_RESET_DONE to StringsEntry("已恢复默认值", "Defaults restored"),
            StringKey.SETTINGS_RESTART_NOTE to
                StringsEntry(
                    "日志容量对下一次连接生效。",
                    "Log capacity applies from the next connection.",
                ),
            // ---- 关于页补充（v2.4.0） ----
            StringKey.ABOUT_COMMIT to StringsEntry("提交：{commit}", "Commit: {commit}"),
            StringKey.ABOUT_COMMIT_UNKNOWN to StringsEntry("未知", "Unknown"),
            StringKey.ABOUT_LICENSE_EMPTY to
                StringsEntry(
                    "未能读取依赖许可列表。",
                    "Dependency license list unavailable.",
                ),
            StringKey.ABOUT_LICENSE_LINE to
                StringsEntry(
                    "{name} {version} · {license} · {scope}",
                    "{name} {version} · {license} · {scope}",
                ),
            StringKey.ABOUT_SCOPE_RUNTIME to StringsEntry("运行时", "runtime"),
            StringKey.ABOUT_SCOPE_ANDROID to StringsEntry("Android", "Android"),
            StringKey.ABOUT_SCOPE_TEST to StringsEntry("测试", "test"),
            StringKey.ABOUT_SCOPE_BUILD to StringsEntry("构建", "build"),
            // ---- 帮助补充（v2.4.0） ----
            StringKey.HELP_Q_PARAM to
                StringsEntry(
                    "参数面板能做什么？",
                    "What can the parameter panel do?",
                ),
            StringKey.HELP_A_PARAM to
                StringsEntry(
                    "参数面板通过 GET_PARAM / SET_PARAM 读写设备参数，写入前在本地校验类型、范围与字节长度。参数表来自协议约定值，固件尚未逐项确认，界面会标注「需固件确认」；设备拒绝时展示错误码。",
                    "The panel reads and writes parameters through GET_PARAM / SET_PARAM, validating type, range, and byte length locally before sending. " +
                        "The table comes from protocol conventions and is not yet confirmed with the firmware, " +
                        "so it is labelled \"Needs firmware confirmation\"; " +
                        "rejections show the device error code.",
                ),
            StringKey.HELP_Q_DIAG to
                StringsEntry(
                    "连接质量统计的数据来自哪里？",
                    "Where does connection quality data come from?",
                ),
            StringKey.HELP_A_DIAG to
                StringsEntry(
                    "全部来自本机报文日志：请求成功率、平均/最大时延、重传、乱序与错误码分布。样本少于 20 条时只提示「样本不足」，不给出结论。",
                    "Everything comes from on-device packet logs: success rate, average/max latency, retransmissions, out-of-order responses, " +
                        "and error-code distribution. Under 20 requests only \"not enough samples\" is shown.",
                ),
            StringKey.HELP_Q_ONBOARDING to
                StringsEntry(
                    "引导会反复弹出吗？",
                    "Will the guide pop up repeatedly?",
                ),
            StringKey.HELP_A_ONBOARDING to
                StringsEntry(
                    "三步引导可跳过，完成后写入本机偏好；可在设置页重新查看。引导不会自动触发系统权限弹窗，只有点击按钮才会走既有权限状态机。",
                    "The three-step guide can be skipped and its completion is stored locally; you can reopen it from Settings. " +
                        "It never triggers the system permission prompt automatically; only a tap runs the existing permission state machine.",
                ),
            // ---- 固件库（v2.5.0） ----
            StringKey.OTA_LIBRARY_TITLE to StringsEntry("固件库", "Firmware library"),
            StringKey.OTA_LIBRARY_EMPTY to
                StringsEntry(
                    "固件库为空：成功或失败升级过一次后，这里会记录包元信息。",
                    "The firmware library is empty. After one OTA attempt, package metadata will appear here.",
                ),
            StringKey.OTA_LIBRARY_NOTE to
                StringsEntry(
                    "只保存文件名、版本、渠道、CRC-32 与原始文件引用，不缓存固件本体。文件失效时需重新选择。",
                    "Only metadata (file name, version, channel, CRC-32) and the original file reference are stored; " +
                        "firmware binaries are never cached. Missing files must be re-selected.",
                ),
            StringKey.OTA_LIBRARY_USE to StringsEntry("从库中使用", "Use from library"),
            StringKey.OTA_LIBRARY_DELETE to StringsEntry("删除", "Delete"),
            StringKey.OTA_LIBRARY_MARK_FAVORITE to StringsEntry("标为常用", "Mark favourite"),
            StringKey.OTA_LIBRARY_UNMARK_FAVORITE to StringsEntry("取消常用", "Unmark favourite"),
            StringKey.OTA_LIBRARY_LAST_USED to
                StringsEntry(
                    "最近：{device} · {outcome}",
                    "Last: {device} · {outcome}",
                ),
            StringKey.OTA_LIBRARY_OUTCOME_SUCCEEDED to StringsEntry("成功", "succeeded"),
            StringKey.OTA_LIBRARY_OUTCOME_FAIL to StringsEntry("失败", "failed"),
            StringKey.OTA_LIBRARY_OUTCOME_ABORTED to StringsEntry("中止", "aborted"),
            StringKey.OTA_LIBRARY_CHECKING to StringsEntry("正在校验可访问性与 CRC…", "Checking file access and CRC…"),
            StringKey.OTA_LIBRARY_AVAILABLE to StringsEntry("校验通过，可直接升级", "Verified, ready for OTA"),
            StringKey.OTA_LIBRARY_MISSING to
                StringsEntry(
                    "文件已失效：{reason}，请重新选择固件。",
                    "File unavailable: {reason}. Please select the firmware again.",
                ),
            StringKey.OTA_LIBRARY_MISSING_ACCESS to StringsEntry("文件已删除或不可访问", "file deleted or inaccessible"),
            StringKey.OTA_LIBRARY_MISSING_READ to StringsEntry("文件无法读取", "file cannot be read"),
            StringKey.OTA_LIBRARY_CRC_MISMATCH to
                StringsEntry(
                    "文件内容与记录不一致（CRC {expected} / 实际 {actual}），请重新选择。",
                    "File content differs from the record (CRC {expected} / actual {actual}). Please select it again.",
                ),
            StringKey.OTA_LIBRARY_USE_FAILED to
                StringsEntry(
                    "无法加载该固件：{detail}，请重新选择。",
                    "Could not load this firmware: {detail}. Please select it again.",
                ),
            // ---- 一键诊断报告（v2.5.0） ----
            StringKey.DIAG_REPORT_TITLE to StringsEntry("一键诊断报告", "One-tap diagnostic report"),
            StringKey.DIAG_REPORT_PREVIEW to
                StringsEntry(
                    "将包含 {sections} 个小节，约 {size}",
                    "{sections} sections, about {size}",
                ),
            StringKey.DIAG_REPORT_SECTION_LIST to StringsEntry("内容：{list}", "Includes: {list}"),
            StringKey.DIAG_REPORT_GENERATE to StringsEntry("生成报告", "Generate report"),
            StringKey.DIAG_REPORT_GENERATING to StringsEntry("生成中… {percent}%", "Generating… {percent}%"),
            StringKey.DIAG_REPORT_CANCEL to StringsEntry("取消生成", "Cancel"),
            StringKey.DIAG_REPORT_COPY to StringsEntry("复制报告", "Copy report"),
            StringKey.DIAG_REPORT_SAVE to StringsEntry("保存为文件", "Save as file"),
            StringKey.DIAG_REPORT_COPIED to StringsEntry("报告已复制", "Report copied"),
            StringKey.DIAG_REPORT_SAVE_SUCCESS to
                StringsEntry(
                    "已保存 {name}（{bytes} 字节）",
                    "Saved {name} ({bytes} bytes)",
                ),
            StringKey.DIAG_REPORT_SAVE_CANCELLED to StringsEntry("已取消保存", "Save cancelled"),
            StringKey.DIAG_REPORT_FAILED to
                StringsEntry(
                    "报告生成失败：{detail}，请重试。",
                    "Report generation failed: {detail}. Please retry.",
                ),
            StringKey.DIAG_REPORT_CANCELLED to StringsEntry("已取消生成", "Generation cancelled"),
            StringKey.DIAG_REPORT_SECTION_DEVICE to StringsEntry("设备信息", "Device"),
            StringKey.DIAG_REPORT_SECTION_FIRMWARE to StringsEntry("固件版本", "Firmware"),
            StringKey.DIAG_REPORT_SECTION_BATTERY to StringsEntry("电量与充电", "Battery"),
            StringKey.DIAG_REPORT_SECTION_CONFIG to StringsEntry("配置当前值", "Config values"),
            StringKey.DIAG_REPORT_SECTION_PARAM to StringsEntry("参数当前值", "Param values"),
            StringKey.DIAG_REPORT_SECTION_ERRORS to StringsEntry("最近错误", "Recent errors"),
            StringKey.DIAG_REPORT_SECTION_QUALITY to StringsEntry("连接质量", "Link quality"),
            StringKey.DIAG_REPORT_SECTION_OTA to StringsEntry("OTA 历史", "OTA history"),
            StringKey.DIAG_REPORT_SECTION_PACKET_LOG to StringsEntry("报文日志", "Packet log"),
            StringKey.DIAG_REPORT_FORMAT_MARKDOWN to StringsEntry("Markdown", "Markdown"),
            StringKey.DIAG_REPORT_FORMAT_TEXT to StringsEntry("纯文本", "Plain text"),
            StringKey.HEALTH_TITLE to StringsEntry("设备体检", "Device health check"),
            StringKey.HEALTH_RUN to StringsEntry("开始体检", "Run health check"),
            StringKey.HEALTH_RUNNING to
                StringsEntry(
                    "体检中 {done}/{total}…",
                    "Running health check {done}/{total}…",
                ),
            StringKey.HEALTH_CANCEL to StringsEntry("中止体检", "Stop check"),
            StringKey.HEALTH_COPIED to StringsEntry("体检结果已复制", "Health check result copied"),
            StringKey.HEALTH_SAVE to StringsEntry("保存体检结果", "Save health check result"),
            StringKey.HEALTH_REPORT_TITLE to StringsEntry("设备体检报告", "Device health report"),
            StringKey.HEALTH_REPORT_HEADER to
                StringsEntry(
                    "生成时间：{time} · App 版本 {version} · 平台 {platform}",
                    "Generated: {time} · App version {version} · Platform {platform}",
                ),
            StringKey.HEALTH_REPORT_SOURCE to
                StringsEntry(
                    "数据来源：本机实时读取，已脱敏",
                    "Source: live reads from this device, redacted",
                ),
            StringKey.HEALTH_REPORT_OVERALL to
                StringsEntry(
                    "总体：{status}（正常 {ok}，异常 {abnormal}，失败 {failed}，跳过 {skipped}）",
                    "Overall: {status} (ok {ok}, abnormal {abnormal}, failed {failed}, skipped {skipped})",
                ),
            StringKey.HEALTH_ITEM_DEVICE_INFO to StringsEntry("设备信息", "Device info"),
            StringKey.HEALTH_ITEM_BATTERY to StringsEntry("电量", "Battery"),
            StringKey.HEALTH_ITEM_FIRMWARE to StringsEntry("固件版本", "Firmware version"),
            StringKey.HEALTH_ITEM_HEARTBEAT to StringsEntry("心跳", "Heartbeat"),
            StringKey.HEALTH_ITEM_CONFIG to StringsEntry("当前配置", "Current config"),
            StringKey.HEALTH_ITEM_PARAM to StringsEntry("参数", "Parameters"),
            StringKey.HEALTH_ITEM_OTA to StringsEntry("最近 OTA 记录", "Recent OTA records"),
            StringKey.HEALTH_STATUS_OK to StringsEntry("正常", "OK"),
            StringKey.HEALTH_STATUS_ABNORMAL to StringsEntry("异常", "Abnormal"),
            StringKey.HEALTH_STATUS_SKIPPED to StringsEntry("跳过", "Skipped"),
            StringKey.HEALTH_STATUS_FAIL to StringsEntry("失败", "Failed"),
            StringKey.HEALTH_OVERALL_ABORTED to
                StringsEntry(
                    "链路已断开，体检中止",
                    "Link lost, health check stopped",
                ),
            StringKey.HEALTH_OVERALL_ABNORMAL to
                StringsEntry(
                    "{count} 项异常，需处理",
                    "{count} abnormal item(s) need attention",
                ),
            StringKey.HEALTH_OVERALL_FAILED to
                StringsEntry(
                    "{count} 项失败，请检查连接后重试",
                    "{count} item(s) failed, check the connection and retry",
                ),
            StringKey.HEALTH_OVERALL_OK to StringsEntry("全部正常", "All checks passed"),
            StringKey.HEALTH_DETAIL_DEVICE to
                StringsEntry(
                    "{vendor} {model}（硬件 {hardware}）",
                    "{vendor} {model} (hardware {hardware})",
                ),
            StringKey.HEALTH_DETAIL_BATTERY to StringsEntry("电量 {percent}%，{state}", "{percent}% battery, {state}"),
            StringKey.HEALTH_DETAIL_BATTERY_LOW to
                StringsEntry(
                    "电量 {percent}%，低于 20%",
                    "{percent}% battery, below 20%",
                ),
            StringKey.HEALTH_DETAIL_FIRMWARE to StringsEntry("{version}（{channel} 渠道）", "{version} ({channel} channel)"),
            StringKey.HEALTH_DETAIL_HEARTBEAT to
                StringsEntry(
                    "运行 {uptime} 秒，电量 {percent}%",
                    "Uptime {uptime}s, battery {percent}%",
                ),
            StringKey.HEALTH_DETAIL_HEARTBEAT_MISMATCH to
                StringsEntry(
                    "电量回读差异：{first}% → {second}%",
                    "Battery read-back mismatch: {first}% → {second}%",
                ),
            StringKey.HEALTH_DETAIL_CONFIG to
                StringsEntry(
                    "已读取 {ok}/{total} 个可见配置键",
                    "Read {ok}/{total} visible config keys",
                ),
            StringKey.HEALTH_DETAIL_CONFIG_PARTIAL to
                StringsEntry(
                    "已读取 {ok}/{total} 个可见配置键，{failed} 个失败，{skipped} 个不支持",
                    "Read {ok}/{total} visible config keys, {failed} failed, {skipped} unsupported",
                ),
            StringKey.HEALTH_DETAIL_PARAM to
                StringsEntry(
                    "已读取 {ok}/{total} 个参数",
                    "Read {ok}/{total} parameters",
                ),
            StringKey.HEALTH_DETAIL_PARAM_PARTIAL to
                StringsEntry(
                    "已读取 {ok}/{total} 个参数，{failed} 个失败，{skipped} 个不支持",
                    "Read {ok}/{total} parameters, {failed} failed, {skipped} unsupported",
                ),
            StringKey.HEALTH_DETAIL_OTA to
                StringsEntry(
                    "本机共 {count} 条，最近：{version}（{outcome}）",
                    "{count} local record(s), latest: {version} ({outcome})",
                ),
            StringKey.HEALTH_DETAIL_DEVICE_REJECTED to StringsEntry("设备拒绝读取", "Device rejected the read"),
            StringKey.HEALTH_CHARGING to StringsEntry("充电中", "charging"),
            StringKey.HEALTH_NOT_CHARGING to StringsEntry("未充电", "not charging"),
            StringKey.HEALTH_SKIP_CONFIG to
                StringsEntry(
                    "当前设备没有能力内可见的配置键",
                    "No visible config keys for this device",
                ),
            StringKey.HEALTH_SKIP_PARAM to StringsEntry("参数表为空", "Parameter table is empty"),
            StringKey.HEALTH_SKIP_OTA to StringsEntry("本机暂无 OTA 记录", "No local OTA records"),
            StringKey.HEALTH_SKIP_UNSUPPORTED to
                StringsEntry(
                    "设备不支持该能力（{code}）",
                    "Device does not support this capability ({code})",
                ),
            StringKey.HEALTH_SKIP_DEVICE_UNSUPPORTED to
                StringsEntry(
                    "设备不支持这些读取项",
                    "Device does not support these reads",
                ),
            StringKey.HEALTH_FAIL_LINK to StringsEntry("链路已断开", "Link lost"),
            StringKey.HEALTH_FAIL_TIMEOUT to StringsEntry("读取超时（已重试）", "Read timed out (retried)"),
            StringKey.HEALTH_FAIL_TRANSPORT to StringsEntry("传输失败", "Transport failure"),
            StringKey.HEALTH_FAIL_UNEXPECTED to StringsEntry("读取失败", "Read failed"),
            StringKey.HEALTH_SUGGEST_LINK to
                StringsEntry(
                    "请重新连接设备后再次体检",
                    "Reconnect the device and run the check again",
                ),
            StringKey.HEALTH_SUGGEST_TIMEOUT to
                StringsEntry(
                    "确认设备在线后重试",
                    "Make sure the device is online and retry",
                ),
            StringKey.HEALTH_SUGGEST_TRANSPORT to
                StringsEntry(
                    "检查连接与距离后重试",
                    "Check the connection and distance, then retry",
                ),
            StringKey.HEALTH_SUGGEST_UNEXPECTED to
                StringsEntry(
                    "重试；若持续失败请查看事件时间线",
                    "Retry; if it keeps failing, check the event timeline",
                ),
            StringKey.HEALTH_SUGGEST_CHARGE to
                StringsEntry(
                    "电量低于 20%，请先充电",
                    "Battery is below 20%, charge the device first",
                ),
            StringKey.HEALTH_SUGGEST_REFRESH to
                StringsEntry(
                    "电量回读不一致，请重新读取",
                    "Battery read-back differs, refresh the readings",
                ),
            StringKey.HEALTH_SUGGEST_DEVICE to
                StringsEntry(
                    "查看设备错误码并按固件说明处理",
                    "Check the device error code and follow the firmware guide",
                ),
            StringKey.HEALTH_SUGGEST_CONFIG to
                StringsEntry(
                    "部分配置项读取失败，可在配置页重试",
                    "Some config reads failed; retry from the config page",
                ),
            StringKey.DASH_TITLE to StringsEntry("设备仪表盘", "Device dashboard"),
            StringKey.DASH_BATTERY to StringsEntry("电量", "Battery"),
            StringKey.DASH_CHARGING to StringsEntry("充电状态", "Charging"),
            StringKey.DASH_FIRMWARE to StringsEntry("固件版本", "Firmware"),
            StringKey.DASH_UPTIME to StringsEntry("运行时长", "Uptime"),
            StringKey.DASH_SIGNAL to StringsEntry("当前信号", "Signal"),
            StringKey.DASH_SIGNAL_SOURCE to
                StringsEntry(
                    "来源：{source} · {time}",
                    "Source: {source} · {time}",
                ),
            StringKey.DASH_SAMPLE_NOTE to
                StringsEntry(
                    "本机采样，非设备历史",
                    "Local samples, not device history",
                ),
            StringKey.DASH_TREND_TITLE to
                StringsEntry(
                    "本机趋势（最近 {count} 点）",
                    "Local trend (latest {count} points)",
                ),
            StringKey.DASH_TREND_INSUFFICIENT to
                StringsEntry(
                    "采样不足 3 点，仅显示当前值",
                    "Fewer than 3 samples, showing current values only",
                ),
            StringKey.DASH_NO_DATA to StringsEntry("暂无数据", "No data"),
            StringKey.DASH_UPTIME_HM to StringsEntry("{hours} 小时 {minutes} 分", "{hours}h {minutes}m"),
            StringKey.DASH_UPTIME_MS to StringsEntry("{minutes} 分 {seconds} 秒", "{minutes}m {seconds}s"),
            StringKey.DASH_SOURCE_SCAN to StringsEntry("最近一次扫描", "Latest scan"),
            StringKey.DASH_SOURCE_CONNECT to StringsEntry("连接时扫描", "Scan at connection"),
            StringKey.OTA_WIZARD_STEP to
                StringsEntry(
                    "第 {current}/5 步 · {title}",
                    "Step {current}/5 · {title}",
                ),
            StringKey.OTA_STEP_SELECT to StringsEntry("选择文件", "Select file"),
            StringKey.OTA_STEP_PRECHECK to StringsEntry("预检结果", "Precheck"),
            StringKey.OTA_STEP_CONFIRM to StringsEntry("升级前确认", "Confirm"),
            StringKey.OTA_STEP_TRANSFER to StringsEntry("传输中", "Transferring"),
            StringKey.OTA_STEP_RESULT to StringsEntry("结果", "Result"),
            StringKey.OTA_NEXT to StringsEntry("下一步", "Next"),
            StringKey.OTA_PREVIOUS to StringsEntry("上一步", "Back"),
            StringKey.OTA_CANCEL to StringsEntry("取消", "Cancel"),
            StringKey.OTA_START_FRESH to StringsEntry("从头开始升级", "Start from the beginning"),
            StringKey.OTA_RESUME_FROM to StringsEntry("从 {percent}% 继续", "Resume from {percent}%"),
            StringKey.OTA_RESUME_INVALID to StringsEntry("断点已失效：{reason}", "Resume point invalid: {reason}"),
            StringKey.OTA_CONFIRM_VERSION to StringsEntry("{from} → {to}", "{from} → {to}"),
            StringKey.OTA_CONFIRM_BATTERY to
                StringsEntry(
                    "当前电量 {percent}%，建议高于 50% 后再升级",
                    "Battery {percent}%, above 50% is recommended",
                ),
            StringKey.OTA_CONFIRM_BATTERY_UNKNOWN to
                StringsEntry(
                    "未读取到电量，请先确认设备电量",
                    "Battery unknown, confirm the device charge first",
                ),
            StringKey.OTA_CONFIRM_KEEP_CONNECTED to
                StringsEntry(
                    "升级过程中请保持连接，避免切换应用或远离设备",
                    "Keep the connection during the update; avoid switching apps or moving away",
                ),
            StringKey.OTA_CONFIRM_RESUME_NOTE to
                StringsEntry(
                    "中断后可从已保存进度继续；断点与当前固件不匹配时需从头开始",
                    "If interrupted, resume from the saved progress; mismatched checkpoints restart from the beginning",
                ),
            StringKey.OTA_RESULT_RESUME_SAVED to
                StringsEntry(
                    "可续传：已保存 {percent}%",
                    "Resumable: {percent}% saved",
                ),
            StringKey.OTA_RESULT_RESUME_NONE to StringsEntry("无可续传断点", "No resumable checkpoint"),
            StringKey.OTA_WIZARD_DONE to StringsEntry("完成", "Done"),
            StringKey.OTA_WIZARD_AGAIN to StringsEntry("再次升级", "Update again"),
            StringKey.OTA_PRECHECK_RULES to StringsEntry("预检规则", "Precheck rules"),
            StringKey.OTA_RULE_STRUCTURE to StringsEntry("文件结构", "File structure"),
            StringKey.OTA_RULE_SIZE to StringsEntry("大小上限", "Size limit"),
            StringKey.OTA_RULE_MODEL to StringsEntry("设备型号", "Device model"),
            StringKey.OTA_RULE_VERSION to StringsEntry("版本方向", "Version direction"),
            StringKey.OTA_RULE_CRC to StringsEntry("内容完整性", "Payload integrity"),
            StringKey.OTA_RULE_SIGNATURE to StringsEntry("固件签名", "Firmware signature"),
            StringKey.OTA_RULE_PASSED to StringsEntry("通过", "Passed"),
            StringKey.OTA_RULE_BLOCKING to StringsEntry("阻断", "Blocking"),
            StringKey.OTA_PRECHECK_WARNINGS to StringsEntry("非阻断提示", "Non-blocking warnings"),
            StringKey.OTA_TARGET_VERSION to StringsEntry("目标版本", "Target version"),
            StringKey.OTA_CURRENT_VERSION to StringsEntry("当前版本", "Current version"),
            StringKey.OTA_WIZARD_NO_SELECTION to StringsEntry("尚未选择固件", "No firmware selected yet"),
            // ---- 设备事件时间线（v2.5.0） ----
            StringKey.TIMELINE_TITLE to StringsEntry("设备事件时间线", "Device event timeline"),
            StringKey.TIMELINE_FILTER_ALL to StringsEntry("全部", "All"),
            StringKey.TIMELINE_EMPTY to StringsEntry("尚未产生事件", "No events yet"),
            StringKey.TIMELINE_TYPE_CONNECTION to StringsEntry("连接", "Connection"),
            StringKey.TIMELINE_TYPE_DISCONNECTION to StringsEntry("断开", "Disconnect"),
            StringKey.TIMELINE_TYPE_AUTH to StringsEntry("鉴权", "Auth"),
            StringKey.TIMELINE_TYPE_CONFIG to StringsEntry("配置", "Config"),
            StringKey.TIMELINE_TYPE_PARAM to StringsEntry("参数", "Param"),
            StringKey.TIMELINE_TYPE_OTA to StringsEntry("OTA", "OTA"),
            StringKey.TIMELINE_TYPE_ERROR to StringsEntry("错误", "Error"),
            StringKey.TIMELINE_EVENT_CONNECTED to StringsEntry("已连接", "Connected"),
            StringKey.TIMELINE_EVENT_RECONNECTED to StringsEntry("已重新连接", "Reconnected"),
            StringKey.TIMELINE_EVENT_DISCONNECTED to StringsEntry("连接已断开", "Disconnected"),
            StringKey.TIMELINE_EVENT_AUTH_OK to StringsEntry("鉴权成功", "Authenticated"),
            StringKey.TIMELINE_EVENT_AUTH_FAILED to
                StringsEntry("鉴权失败，请重新输入密钥后重试", "Authentication failed; please re-enter the key and retry"),
            StringKey.TIMELINE_EVENT_CONFIG_SAVED to StringsEntry("配置已写入", "Configuration written"),
            StringKey.TIMELINE_EVENT_CONFIG_FAILED to StringsEntry("配置写入失败，请重试", "Configuration write failed; please retry"),
            StringKey.TIMELINE_EVENT_PARAM_SAVED to StringsEntry("参数已写入", "Parameter written"),
            StringKey.TIMELINE_EVENT_PARAM_FAILED to StringsEntry("参数写入失败，请重试", "Parameter write failed; please retry"),
            StringKey.TIMELINE_EVENT_OTA_STAGE to StringsEntry("OTA 阶段：{stage}", "OTA stage: {stage}"),
            StringKey.TIMELINE_EVENT_OTA_DONE to StringsEntry("OTA 完成", "OTA completed"),
            StringKey.TIMELINE_EVENT_OTA_FAILED to StringsEntry("OTA 失败，请查看错误码后重试", "OTA failed; check the error code and retry"),
            StringKey.TIMELINE_EVENT_OTA_ABORTED to StringsEntry("OTA 已中止，可从断点继续", "OTA aborted; you can resume from the checkpoint"),
            StringKey.TIMELINE_EVENT_ERROR to StringsEntry("命令失败：{detail}", "Command failed: {detail}"),
            // ---- 本地提醒（v2.5.0） ----
            StringKey.SETTINGS_NOTIFY_SECTION to StringsEntry("本地提醒", "Local notifications"),
            StringKey.SETTINGS_NOTIFY_DEFAULT_OFF to
                StringsEntry(
                    "提醒默认全部关闭；只在本 App 前台或系统允许时触发，不上传任何数据。",
                    "All reminders are off by default. They only fire while the app is running and never upload data.",
                ),
            StringKey.SETTINGS_NOTIFY_OTA to StringsEntry("OTA 完成或失败时提醒", "Notify when OTA finishes or fails"),
            StringKey.SETTINGS_NOTIFY_DISCONNECT to StringsEntry("连接断开时提醒", "Notify when the connection drops"),
            StringKey.SETTINGS_NOTIFY_LOW_BATTERY to StringsEntry("低电量时提醒", "Notify on low battery"),
            StringKey.SETTINGS_NOTIFY_THRESHOLD to StringsEntry("低电量阈值（%）", "Low battery threshold (%)"),
            StringKey.SETTINGS_NOTIFY_PERMISSION_GRANTED to StringsEntry("通知权限已开启", "Notification permission granted"),
            StringKey.SETTINGS_NOTIFY_PERMISSION_DENIED to
                StringsEntry(
                    "通知权限已被拒绝，请到系统设置开启。",
                    "Notification permission was denied. Please enable it in system settings.",
                ),
            StringKey.SETTINGS_NOTIFY_PERMISSION_REQUEST to
                StringsEntry(
                    "开启提醒需要系统通知权限，点击开关时才会请求。",
                    "Local reminders need notification permission; it is only requested when you turn a switch on.",
                ),
            StringKey.SETTINGS_NOTIFY_UNSUPPORTED to
                StringsEntry(
                    "当前平台不支持本地通知，开关会保存但不生效。",
                    "Local notifications are not supported on this platform; switches are saved but have no effect.",
                ),
            StringKey.SETTINGS_NOTIFY_OPEN_SETTINGS to StringsEntry("打开系统设置", "Open system settings"),
            StringKey.NOTIFICATION_OTA_SUCCESS_TITLE to StringsEntry("固件升级成功", "Firmware update succeeded"),
            StringKey.NOTIFICATION_OTA_SUCCESS_BODY to StringsEntry("{version} 升级成功", "{version} updated successfully"),
            StringKey.NOTIFICATION_OTA_FAILED_TITLE to StringsEntry("固件升级失败", "Firmware update failed"),
            StringKey.NOTIFICATION_OTA_FAILED_BODY to StringsEntry("{version} 升级失败：{reason}", "{version} update failed: {reason}"),
            StringKey.NOTIFICATION_DISCONNECTED_TITLE to StringsEntry("设备已断开", "Device disconnected"),
            StringKey.NOTIFICATION_DISCONNECTED_BODY to StringsEntry("{device} 连接已断开", "{device} disconnected"),
            StringKey.NOTIFICATION_LOW_BATTERY_TITLE to StringsEntry("设备电量偏低", "Device battery low"),
            StringKey.NOTIFICATION_LOW_BATTERY_BODY to
                StringsEntry(
                    "{device} 电量 {percent}%（阈值 {threshold}%）",
                    "{device} battery at {percent}% (threshold {threshold}%)",
                ),
            // ---- 配置预设（v2.7.0） ----
            StringKey.PRESET_SECTION_TITLE to StringsEntry("配置预设（场景方案）", "Configuration presets"),
            StringKey.PRESET_EMPTY to
                StringsEntry(
                    "还没有预设。先读取当前配置，再保存为一套方案。",
                    "No presets yet. Load the current configuration, then save it as a scenario.",
                ),
            StringKey.PRESET_SAVE_CURRENT to StringsEntry("保存当前配置为预设", "Save current configuration as preset"),
            StringKey.PRESET_NAME_LABEL to StringsEntry("预设名称", "Preset name"),
            StringKey.PRESET_RENAME_TITLE to StringsEntry("重命名预设", "Rename preset"),
            StringKey.PRESET_DELETE_TITLE to StringsEntry("删除预设", "Delete preset"),
            StringKey.PRESET_DELETE_MESSAGE to
                StringsEntry(
                    "将删除「{name}」，不影响设备当前配置。",
                    "Delete \"{name}\"? The device configuration is not changed.",
                ),
            StringKey.PRESET_APPLY to StringsEntry("应用", "Apply"),
            StringKey.PRESET_RENAME to StringsEntry("重命名", "Rename"),
            StringKey.PRESET_DELETE to StringsEntry("删除", "Delete"),
            StringKey.PRESET_DUPLICATE to StringsEntry("复制", "Duplicate"),
            StringKey.PRESET_APPLY_TITLE to StringsEntry("应用预设「{name}」", "Apply preset \"{name}\""),
            StringKey.PRESET_APPLY_CONFIRM to StringsEntry("确认写入", "Write changes"),
            StringKey.PRESET_DIFF_CURRENT_TO_TARGET to StringsEntry("{current} → {target}", "{current} → {target}"),
            StringKey.PRESET_STATUS_CHANGE to StringsEntry("将修改", "Will change"),
            StringKey.PRESET_STATUS_ALREADY to StringsEntry("已是目标值", "Already target"),
            StringKey.PRESET_STATUS_UNSUPPORTED to StringsEntry("设备不支持", "Unsupported"),
            StringKey.PRESET_RESULT_TITLE to StringsEntry("应用结果", "Apply result"),
            StringKey.PRESET_RESULT_WRITTEN to StringsEntry("已写入", "Written"),
            StringKey.PRESET_RESULT_ALREADY to StringsEntry("已是目标值", "Already target"),
            StringKey.PRESET_RESULT_SKIPPED to StringsEntry("已跳过", "Skipped"),
            StringKey.PRESET_RESULT_FAILED_LABEL to StringsEntry("失败", "Failed"),
            StringKey.PRESET_RESULT_SUMMARY to
                StringsEntry(
                    "写入 {written}，已是目标 {already}，跳过 {skipped}，失败 {failed}",
                    "Written {written}, already target {already}, skipped {skipped}, failed {failed}",
                ),
            StringKey.PRESET_SAVED_TOAST to StringsEntry("已保存预设「{name}」", "Preset \"{name}\" saved"),
            StringKey.PRESET_RENAMED_TOAST to StringsEntry("已重命名预设", "Preset renamed"),
            StringKey.PRESET_DELETED_TOAST to StringsEntry("已删除预设", "Preset deleted"),
            StringKey.PRESET_DUPLICATED_TOAST to StringsEntry("已复制预设", "Preset duplicated"),
            StringKey.PRESET_NOT_LOADED to
                StringsEntry(
                    "当前配置尚未读取，先读取后再保存或应用预设。",
                    "The current configuration is not loaded yet; load it before saving or applying a preset.",
                ),
            StringKey.PRESET_NEVER_USED to StringsEntry("尚未使用", "Never used"),
            StringKey.PRESET_KEY_COUNT to StringsEntry("{count} 个配置键", "{count} keys"),
            StringKey.PRESET_DEFAULT_NAME to StringsEntry("场景 {index}", "Scenario {index}"),
            StringKey.PRESET_NO_DIFF to
                StringsEntry(
                    "当前配置已全部是目标值，无需写入。",
                    "Every key already matches the preset; nothing to write.",
                ),
            // ---- 自动化规则（v2.7.0） ----
            StringKey.SETTINGS_AUTOMATION_ENTRY to StringsEntry("自动化规则", "Automation rules"),
            StringKey.SETTINGS_AUTOMATION_DESC to
                StringsEntry(
                    "连接或 OTA 成功后自动执行本地动作",
                    "Run local actions after connect or OTA success",
                ),
            StringKey.AUTOMATION_TITLE to StringsEntry("自动化规则", "Automation rules"),
            StringKey.AUTOMATION_FOREGROUND_ONLY to
                StringsEntry(
                    "仅在本 App 前台运行期间触发，不做后台唤起。",
                    "Rules only run while this app is in the foreground; no background wake-up.",
                ),
            StringKey.AUTOMATION_EMPTY to
                StringsEntry(
                    "还没有规则。新增后可开关、排序或删除。",
                    "No rules yet. Add one, then enable, reorder or delete it.",
                ),
            StringKey.AUTOMATION_ADD to StringsEntry("新增规则", "Add rule"),
            StringKey.AUTOMATION_TRIGGER to StringsEntry("触发时机", "Trigger"),
            StringKey.AUTOMATION_ACTION to StringsEntry("执行动作", "Action"),
            StringKey.AUTOMATION_ENABLED to StringsEntry("启用", "Enabled"),
            StringKey.AUTOMATION_DELETE to StringsEntry("删除", "Delete"),
            StringKey.AUTOMATION_MOVE_UP to StringsEntry("上移", "Move up"),
            StringKey.AUTOMATION_MOVE_DOWN to StringsEntry("下移", "Move down"),
            StringKey.AUTOMATION_TRIGGER_CONNECTED to StringsEntry("连接成功后", "After connection"),
            StringKey.AUTOMATION_TRIGGER_OTA to StringsEntry("OTA 成功后", "After OTA success"),
            StringKey.AUTOMATION_ACTION_READ_INFO to StringsEntry("读取设备信息", "Read device info"),
            StringKey.AUTOMATION_ACTION_READ_BATTERY to StringsEntry("读取电量", "Read battery"),
            StringKey.AUTOMATION_ACTION_HEALTH to StringsEntry("执行设备体检", "Run health check"),
            StringKey.AUTOMATION_ACTION_APPLY_PRESET to StringsEntry("应用配置预设", "Apply a preset"),
            StringKey.AUTOMATION_ACTION_READ_FIRMWARE to StringsEntry("读取固件版本", "Read firmware version"),
            StringKey.AUTOMATION_ACTION_DIAGNOSTIC to StringsEntry("生成诊断报告", "Generate diagnostic report"),
            StringKey.AUTOMATION_PRESET_SELECT to StringsEntry("选择预设", "Preset"),
            StringKey.AUTOMATION_NO_PRESET to
                StringsEntry(
                    "暂无预设，请先在设备详情页保存一套场景方案。",
                    "No presets yet; save a scenario on the device detail page first.",
                ),
            StringKey.AUTOMATION_SAVE_RULE to StringsEntry("添加规则", "Add rule"),
            StringKey.AUTOMATION_RULE_ADDED_TOAST to StringsEntry("规则已添加", "Rule added"),
            StringKey.AUTOMATION_EVENT_SUMMARY to StringsEntry("自动化：{action}", "Automation: {action}"),
            StringKey.AUTOMATION_LINK_LOST to StringsEntry("链路已断开，自动化未执行", "Link lost; automation skipped"),
            // ---- 使用统计（v2.7.0） ----
            StringKey.SETTINGS_STATS_ENTRY to StringsEntry("使用统计", "Usage statistics"),
            StringKey.SETTINGS_STATS_DESC to StringsEntry("本机真实使用数据，不上传", "Real local usage data, never uploaded"),
            StringKey.STATS_TITLE to StringsEntry("使用统计", "Usage statistics"),
            StringKey.STATS_LOCAL_ONLY to
                StringsEntry(
                    "仅本机统计，不上传。所有数值来自设备档案、OTA 历史与事件时间线。",
                    "Local only, never uploaded. Values come from device profiles, OTA history and the event timeline.",
                ),
            StringKey.STATS_RANGE_7 to StringsEntry("近 7 天", "Last 7 days"),
            StringKey.STATS_RANGE_30 to StringsEntry("近 30 天", "Last 30 days"),
            StringKey.STATS_RANGE_ALL to StringsEntry("全部", "All time"),
            StringKey.STATS_CONNECTIONS to StringsEntry("连接次数", "Connections"),
            StringKey.STATS_CONNECTION_AVG to StringsEntry("平均连接时长", "Average session"),
            StringKey.STATS_OTA to StringsEntry("OTA 次数", "OTA attempts"),
            StringKey.STATS_OTA_SUCCESS_RATE to StringsEntry("OTA 成功率", "OTA success rate"),
            StringKey.STATS_OTA_AVG to StringsEntry("OTA 平均耗时", "Average OTA duration"),
            StringKey.STATS_CONFIG_WRITES to StringsEntry("配置写入次数", "Configuration writes"),
            StringKey.STATS_PARAM_WRITES to StringsEntry("参数写入次数", "Parameter writes"),
            StringKey.STATS_HEALTH to StringsEntry("体检次数", "Health checks"),
            StringKey.STATS_DEVICES to StringsEntry("按设备分布", "Per-device distribution"),
            StringKey.STATS_NO_DATA to StringsEntry("暂无数据", "No data"),
            StringKey.STATS_COUNT_UNIT to StringsEntry("{count} 次", "{count}"),
            StringKey.STATS_DURATION_UNIT to StringsEntry("{seconds} 秒", "{seconds}s"),
            StringKey.STATS_DEVICE_ROW to
                StringsEntry(
                    "{connections} 次连接 · {ota} 次 OTA · {writes} 次写入 · {health} 次体检",
                    "{connections} connections · {ota} OTA · {writes} writes · {health} health checks",
                ),
            StringKey.STATS_LAST_CONNECTED to StringsEntry("最近连接：{time}", "Last connected: {time}"),
            // ---- 偏好与预设备份（v2.7.0） ----
            StringKey.SETTINGS_BACKUP_ENTRY to StringsEntry("偏好与预设备份", "Preferences and presets backup"),
            StringKey.SETTINGS_BACKUP_DESC to
                StringsEntry(
                    "导出/导入 JSON，或恢复出厂设置",
                    "Export or import JSON, or restore defaults",
                ),
            StringKey.BACKUP_TITLE to StringsEntry("偏好与预设备份", "Preferences and presets backup"),
            StringKey.BACKUP_EXPORT to StringsEntry("导出备份", "Export backup"),
            StringKey.BACKUP_EXPORT_DESC to StringsEntry("保存为 JSON 文件", "Save as a JSON file"),
            StringKey.BACKUP_IMPORT to StringsEntry("导入备份", "Import backup"),
            StringKey.BACKUP_IMPORT_DESC to StringsEntry("粘贴 JSON 后校验并预览", "Paste JSON, then validate and preview"),
            StringKey.BACKUP_FIELDS_TITLE to StringsEntry("备份包含", "Included"),
            StringKey.BACKUP_FIELD_LIST to
                StringsEntry(
                    "语言、主题、重连策略、日志容量、导出默认值、预设、自动化规则、引导标记",
                    "Language, theme, reconnect policy, log capacity, export defaults, presets, automation rules, onboarding flag",
                ),
            StringKey.BACKUP_EXCLUDED_TITLE to StringsEntry("明确排除", "Excluded"),
            StringKey.BACKUP_EXCLUDED_LIST to
                StringsEntry(
                    "令牌、密钥、凭证、设备档案、日志、断点",
                    "Tokens, keys, credentials, device profiles, logs, checkpoints",
                ),
            StringKey.BACKUP_JSON_LABEL to StringsEntry("备份 JSON", "Backup JSON"),
            StringKey.BACKUP_VALIDATE to StringsEntry("校验并预览", "Validate and preview"),
            StringKey.BACKUP_PREVIEW_TITLE to StringsEntry("将覆盖以下项", "These items will be overwritten"),
            StringKey.BACKUP_PREVIEW_ROW to StringsEntry("{current} → {incoming}", "{current} → {incoming}"),
            StringKey.BACKUP_IMPORT_CONFIRM to StringsEntry("确认导入", "Import"),
            StringKey.BACKUP_ISSUES_TITLE to StringsEntry("以下字段已回退默认", "Fields reverted to defaults"),
            StringKey.BACKUP_ISSUE_MISSING to StringsEntry("{field}：缺失，已回退默认", "{field}: missing, default used"),
            StringKey.BACKUP_ISSUE_INVALID to StringsEntry("{field}：非法，已回退默认", "{field}: invalid, default used"),
            StringKey.BACKUP_IMPORTED_TOAST to StringsEntry("备份已导入", "Backup imported"),
            StringKey.STORAGE_RECOVERY_TITLE to StringsEntry("存储恢复", "Storage recovery"),
            StringKey.BACKUP_PARSE_FAIL to
                StringsEntry(
                    "导入失败：{reason}。请检查备份内容后重试。",
                    "Import failed: {reason}. Check the backup content and try again.",
                ),
            StringKey.BACKUP_FACTORY_RESET to StringsEntry("恢复出厂设置", "Restore defaults"),
            StringKey.BACKUP_FACTORY_RESET_DESC to
                StringsEntry(
                    "清空偏好与预设；不删除设备档案与令牌",
                    "Clear preferences and presets; device profiles and tokens are kept",
                ),
            StringKey.BACKUP_FACTORY_RESET_TITLE to StringsEntry("确认恢复出厂设置", "Confirm restore defaults"),
            StringKey.BACKUP_FACTORY_RESET_MESSAGE to
                StringsEntry(
                    "将清空语言、主题、重连策略、日志容量、导出默认值、预设、自动化规则与引导标记。",
                    "This clears language, theme, reconnect policy, log capacity, export defaults, presets, automation rules and the onboarding flag.",
                ),
            StringKey.BACKUP_FACTORY_RESET_KEEP to
                StringsEntry(
                    "不会删除设备档案、令牌、日志与断点。",
                    "Device profiles, tokens, logs and checkpoints are not deleted.",
                ),
            StringKey.BACKUP_FACTORY_RESET_DONE to StringsEntry("已恢复出厂设置", "Defaults restored"),
            StringKey.BACKUP_PASTE_HINT to StringsEntry("在此粘贴备份 JSON", "Paste backup JSON here"),
            StringKey.BACKUP_SECTION_LANGUAGE to StringsEntry("语言", "Language"),
            StringKey.BACKUP_SECTION_THEME to StringsEntry("主题", "Theme"),
            StringKey.BACKUP_SECTION_RECONNECT to StringsEntry("重连策略", "Reconnect policy"),
            StringKey.BACKUP_SECTION_LOG_CAPACITY to StringsEntry("日志容量", "Log capacity"),
            StringKey.BACKUP_SECTION_EXPORT_DEFAULTS to StringsEntry("导出默认值", "Export defaults"),
            StringKey.BACKUP_SECTION_PRESETS to StringsEntry("预设", "Presets"),
            StringKey.BACKUP_SECTION_AUTOMATION_RULES to StringsEntry("自动化规则", "Automation rules"),
            StringKey.BACKUP_SECTION_ONBOARDING to StringsEntry("引导标记", "Onboarding flag"),
            StringKey.BACKUP_EXCLUDED_CONTENT to
                StringsEntry(
                    "导出内容不包含令牌、密钥、凭证、设备档案、日志与断点。",
                    "The export never contains tokens, keys, credentials, device profiles, logs or checkpoints.",
                ),
            // ---- 时间线新增类型（v2.7.0） ----
            StringKey.TIMELINE_TYPE_HEALTH to StringsEntry("体检", "Health"),
            StringKey.TIMELINE_TYPE_AUTOMATION to StringsEntry("自动化", "Automation"),
            // ---- 隐私清单新增项（v2.7.0） ----
            StringKey.PRIVACY_ITEM_PRESETS to
                StringsEntry(
                    "配置预设：场景方案的配置值，仅存本机。",
                    "Configuration presets: scenario values stored locally only.",
                ),
            StringKey.PRIVACY_ITEM_AUTOMATION to
                StringsEntry(
                    "自动化规则：触发时机、动作与顺序，仅存本机。",
                    "Automation rules: trigger, action and order stored locally only.",
                ),
            // ---- 实时信号与通知（v2.8.0） ----
            StringKey.DASH_SOURCE_LIVE to StringsEntry("连接实时", "Live connection"),
            StringKey.DASH_RSSI_DEGRADED to
                StringsEntry(
                    "实时信号不可用（{reason}），已回退到扫描快照：{source}。",
                    "Live signal unavailable ({reason}); fell back to the scan snapshot: {source}.",
                ),
            StringKey.DASH_RSSI_READ_ERROR to StringsEntry("读取失败", "read failed"),
            StringKey.DASH_RSSI_DISCONNECTED to StringsEntry("连接已断开", "disconnected"),
            StringKey.DASH_LEGACY_METRICS to
                StringsEntry(
                    "检测到旧版本采样格式，已按空数据处理；新样本会继续追加。",
                    "Legacy metric format detected and treated as empty; new samples will still be appended.",
                ),
            StringKey.SETTINGS_RSSI_SECTION to StringsEntry("实时信号", "Live signal"),
            StringKey.SETTINGS_RSSI_ENABLED to
                StringsEntry(
                    "连接期间订阅实时 RSSI（断开后自动停止，重连后恢复）",
                    "Subscribe to live RSSI while connected (stops on disconnect, resumes on reconnect)",
                ),
            StringKey.SETTINGS_RSSI_PERIOD to
                StringsEntry(
                    "采样周期（秒，1–10）：{seconds}",
                    "Sample interval (seconds, 1–10): {seconds}",
                ),
            StringKey.SETTINGS_NOTIFY_MASTER to StringsEntry("本地通知总开关", "Local notifications master switch"),
            StringKey.SETTINGS_NOTIFY_RECONNECT to
                StringsEntry(
                    "自动重连失败达上限时提醒",
                    "Notify when automatic reconnect exhausts its attempts",
                ),
            StringKey.SETTINGS_NOTIFY_HEALTH to
                StringsEntry(
                    "体检发现异常项时提醒",
                    "Notify when a health check finds issues",
                ),
            StringKey.SETTINGS_NOTIFY_BACKGROUND_NOTE to
                StringsEntry(
                    "通知只在本 App 存活期间送出，不承诺进程被杀后仍能提醒；不包含远程推送。",
                    "Notifications are only delivered while this app is alive; no delivery after the process is killed, and no remote push.",
                ),
            StringKey.NOTIFICATION_RECONNECT_EXHAUSTED_TITLE to
                StringsEntry(
                    "自动重连失败",
                    "Automatic reconnect failed",
                ),
            StringKey.NOTIFICATION_RECONNECT_EXHAUSTED_BODY to
                StringsEntry(
                    "{device} 在 {attempts} 次尝试后仍未连上，请在设备页手动重试。",
                    "{device} is still unreachable after {attempts} attempts; retry manually from the devices page.",
                ),
            StringKey.NOTIFICATION_HEALTH_ABNORMAL_TITLE to
                StringsEntry(
                    "体检发现异常",
                    "Health check found issues",
                ),
            StringKey.NOTIFICATION_HEALTH_ABNORMAL_BODY to
                StringsEntry(
                    "{device} 有 {count} 项异常，点击查看体检报告。",
                    "{device} has {count} abnormal items; tap to view the report.",
                ),
            StringKey.NOTIFICATION_BANNER_TITLE to
                StringsEntry(
                    "通知权限未开启，改为应用内横幅",
                    "Notifications are off; showing an in-app banner",
                ),
            // ---- 诊断包（v2.8.0） ----
            StringKey.DIAG_PACKAGE_TITLE to StringsEntry("诊断包导出", "Diagnostic package"),
            StringKey.DIAG_PACKAGE_HINT to
                StringsEntry(
                    "打包体检报告、连接质量、最近报文（已脱敏）、设备档案摘要、版本信息与指标采样。",
                    "Bundles the health report, connection quality, recent packets (redacted), a profile summary, version info and metric samples.",
                ),
            StringKey.DIAG_PACKAGE_EXPORT to StringsEntry("导出诊断包", "Export diagnostic package"),
            StringKey.DIAG_PACKAGE_EXPORTING to StringsEntry("正在生成诊断包…", "Generating diagnostic package…"),
            StringKey.DIAG_PACKAGE_CANCEL to StringsEntry("取消导出", "Cancel export"),
            StringKey.DIAG_PACKAGE_SAVE_SUCCESS to StringsEntry("诊断包已保存", "Diagnostic package saved"),
            StringKey.DIAG_PACKAGE_SAVE_CANCELLED to StringsEntry("已取消保存", "Save cancelled"),
            StringKey.DIAG_PACKAGE_FAILED to
                StringsEntry(
                    "诊断包生成失败：{reason}。请重试。",
                    "Diagnostic package generation failed: {reason}. Please retry.",
                ),
            StringKey.DIAG_PACKAGE_CANCELLED to StringsEntry("已取消生成诊断包", "Diagnostic package generation cancelled"),
            StringKey.DIAG_PACKAGE_EXCLUDED to
                StringsEntry(
                    "不包含：令牌、密钥、凭据、断点文件、固件二进制、未脱敏原文。",
                    "Excluded: tokens, keys, credentials, checkpoints, firmware binaries and unredacted raw text.",
                ),
            StringKey.DIAG_PACKAGE_REDACTED to StringsEntry("已脱敏", "Redacted"),
            StringKey.DIAG_PACKAGE_TRUNCATED to StringsEntry("部分条目已按上限截断", "Some entries were truncated at the limit"),
            StringKey.DIAG_PACKAGE_ENTRIES to
                StringsEntry(
                    "条目：{names}",
                    "Entries: {names}",
                ),
            // ---- 批量操作（v2.9.0） ----
            StringKey.BATCH_TITLE to StringsEntry("批量操作", "Batch operations"),
            StringKey.BATCH_HINT to
                StringsEntry(
                    "串行执行，避免蓝牙并发冲突；单台失败只标记该台并继续下一台。",
                    "Runs serially to avoid Bluetooth contention; a failed device is marked and the batch continues.",
                ),
            StringKey.BATCH_TARGETS to StringsEntry("选择设备", "Select devices"),
            StringKey.BATCH_KIND_HEALTH to StringsEntry("批量体检", "Batch health check"),
            StringKey.BATCH_KIND_PRESET to StringsEntry("批量应用预设", "Apply preset in batch"),
            StringKey.BATCH_PRESET_LABEL to StringsEntry("配置预设", "Configuration preset"),
            StringKey.BATCH_PRESET_EMPTY to
                StringsEntry(
                    "暂无预设，请先在设备页保存一个预设。",
                    "No presets yet. Save one from a device page first.",
                ),
            StringKey.BATCH_START to StringsEntry("开始执行", "Start"),
            StringKey.BATCH_NO_TARGET to
                StringsEntry(
                    "请至少选择一台设备。",
                    "Select at least one device.",
                ),
            StringKey.BATCH_RUNNING to StringsEntry("批量执行中…", "Running…"),
            StringKey.BATCH_CANCEL_REMAINING to StringsEntry("取消剩余", "Cancel remaining"),
            StringKey.BATCH_CANCEL_REQUESTED to
                StringsEntry(
                    "已请求取消剩余任务，当前设备完成后停止。",
                    "Remaining items will be cancelled after the current device finishes.",
                ),
            StringKey.BATCH_LOCK_NOTE to
                StringsEntry(
                    "执行期间会锁定参与设备的配置、参数、OTA 与快捷操作；未参与设备不受影响。",
                    "Participating devices are locked against config, parameter, OTA and quick-action writes; other devices are unaffected.",
                ),
            StringKey.BATCH_LOCKED_HINT to
                StringsEntry(
                    "批量任务执行中，该设备的写操作已锁定。",
                    "A batch task is running; writes to this device are locked.",
                ),
            StringKey.BATCH_STATUS_QUEUED to StringsEntry("排队中", "Queued"),
            StringKey.BATCH_STATUS_RUNNING to StringsEntry("执行中", "Running"),
            StringKey.BATCH_STATUS_SUCCEEDED to StringsEntry("成功", "Succeeded"),
            StringKey.BATCH_STATUS_SKIPPED to StringsEntry("跳过", "Skipped"),
            StringKey.BATCH_STATUS_FAILURE to StringsEntry("失败", "Failed"),
            StringKey.BATCH_STATUS_CANCELLED to StringsEntry("已取消", "Cancelled"),
            StringKey.BATCH_STATUS_REMOVED to StringsEntry("已移除", "Removed"),
            StringKey.BATCH_SUMMARY_TITLE to StringsEntry("批量结果", "Batch result"),
            StringKey.BATCH_SUMMARY_COUNTS to
                StringsEntry(
                    "共 {total} 台：成功 {succeeded}，跳过 {skipped}，失败 {failed}，取消 {cancelled}，移除 {removed}",
                    "{total} devices: {succeeded} succeeded, {skipped} skipped, {failed} failed, {cancelled} cancelled, {removed} removed",
                ),
            StringKey.BATCH_STEP_STARTED to StringsEntry("开始", "Started"),
            StringKey.BATCH_STEP_HEALTH to StringsEntry("体检", "Health check"),
            StringKey.BATCH_STEP_HEALTH_OK to StringsEntry("体检完成", "Health check complete"),
            StringKey.BATCH_STEP_PRESET to
                StringsEntry(
                    "应用预设「{name}」",
                    "Apply preset \"{name}\"",
                ),
            StringKey.BATCH_STEP_PRESET_WRITTEN to
                StringsEntry(
                    "写入 {written} 项，跳过 {skipped} 项，失败 {failed} 项",
                    "{written} written, {skipped} skipped, {failed} failed",
                ),
            StringKey.BATCH_REASON_DEVICE_LOCKED to
                StringsEntry(
                    "设备正被其它操作占用，已跳过",
                    "Device is busy with another operation; skipped",
                ),
            StringKey.BATCH_REASON_DISCONNECTED to
                StringsEntry(
                    "设备已断开，已移出队列",
                    "Device disconnected and was removed from the queue",
                ),
            StringKey.BATCH_REASON_DISCONNECTED_AFTER to
                StringsEntry(
                    "步骤完成后断开，已移出队列",
                    "Disconnected after the step and was removed from the queue",
                ),
            StringKey.BATCH_REASON_CANCELLED_REMAINING to
                StringsEntry(
                    "已取消剩余任务",
                    "Remaining tasks cancelled",
                ),
            StringKey.BATCH_REASON_JOB_CANCELLED to StringsEntry("任务被取消", "Task cancelled"),
            StringKey.BATCH_REASON_EXECUTION_ERROR to StringsEntry("执行出错", "Execution error"),
            StringKey.BATCH_REASON_NO_PRESET to StringsEntry("未选择预设", "No preset selected"),
            StringKey.BATCH_COPY to StringsEntry("复制汇总", "Copy summary"),
            StringKey.BATCH_COPIED to StringsEntry("已复制汇总", "Summary copied"),
            StringKey.BATCH_SAVE to StringsEntry("保存汇总", "Save summary"),
            StringKey.BATCH_SAVED to StringsEntry("已保存：{name}", "Saved: {name}"),
            StringKey.BATCH_SAVE_CANCELLED to StringsEntry("已取消保存", "Save cancelled"),
            StringKey.BATCH_SAVE_FAILED to
                StringsEntry(
                    "保存失败：{detail}。请重试，或改用复制。",
                    "Save failed: {detail}. Retry, or use copy instead.",
                ),
            StringKey.BATCH_SAVE_UNAVAILABLE to
                StringsEntry(
                    "当前平台不支持保存文件",
                    "Saving files is unavailable on this platform",
                ),
            // ---- 固件库增强（v2.9.0） ----
            StringKey.LIB_IMPORT to StringsEntry("导入固件", "Import firmware"),
            StringKey.LIB_IMPORTING to StringsEntry("正在导入…", "Importing…"),
            StringKey.LIB_IMPORT_SUCCESS to StringsEntry("已导入：{name}", "Imported: {name}"),
            StringKey.LIB_IMPORT_DUPLICATE to
                StringsEntry(
                    "已存在同一固件：{name}",
                    "The same firmware already exists: {name}",
                ),
            StringKey.LIB_IMPORT_REJECTED to
                StringsEntry(
                    "拒绝导入（{code}）：{detail}",
                    "Import rejected ({code}): {detail}",
                ),
            StringKey.LIB_IMPORT_CANCELLED to StringsEntry("已取消导入", "Import cancelled"),
            StringKey.LIB_IMPORT_NOTE to
                StringsEntry(
                    "导入会解析头部并复核 CRC；签名与型号/版本预检仍在使用前完整执行。",
                    "Import parses the header and verifies CRC; signature, model and version prechecks still run in full before use.",
                ),
            StringKey.LIB_FILTER_MODEL to StringsEntry("型号筛选：{model}", "Model filter: {model}"),
            StringKey.LIB_FILTER_MODEL_ALL to StringsEntry("全部型号", "All models"),
            StringKey.LIB_FILTER_VERSION to StringsEntry("版本筛选", "Version filter"),
            StringKey.LIB_SORT_LABEL to StringsEntry("排序", "Sort"),
            StringKey.LIB_SORT_RECENT to StringsEntry("最近使用", "Recent"),
            StringKey.LIB_SORT_VERSION to StringsEntry("按版本", "By version"),
            StringKey.LIB_RENAME to StringsEntry("别名", "Alias"),
            StringKey.LIB_RENAME_TITLE to StringsEntry("重命名别名", "Rename alias"),
            StringKey.LIB_RENAME_HINT to
                StringsEntry(
                    "留空恢复原始文件名",
                    "Leave empty to use the original file name",
                ),
            StringKey.LIB_APPLICABLE to StringsEntry("适用", "Applicable"),
            StringKey.LIB_INAPPLICABLE to StringsEntry("不适用", "Not applicable"),
            StringKey.LIB_REASON_MODEL_UNKNOWN to StringsEntry("设备型号未知", "Device model unknown"),
            StringKey.LIB_REASON_MODEL_MISMATCH to StringsEntry("型号不匹配", "Model mismatch"),
            StringKey.LIB_REASON_VERSION_UNKNOWN to
                StringsEntry(
                    "设备版本未知",
                    "Device version unknown",
                ),
            StringKey.LIB_REASON_VERSION_UNPARSEABLE to
                StringsEntry(
                    "固件版本无法解析",
                    "Firmware version cannot be parsed",
                ),
            StringKey.LIB_REASON_VERSION_ROLLBACK to
                StringsEntry(
                    "版本低于当前",
                    "Lower than the current version",
                ),
            StringKey.LIB_SELECT_LATEST to
                StringsEntry(
                    "选中最新的适用包",
                    "Select latest applicable",
                ),
            StringKey.LIB_NO_APPLICABLE to
                StringsEntry(
                    "没有适用该设备的固件包",
                    "No applicable package for this device",
                ),
            StringKey.LIB_UNAVAILABLE to
                StringsEntry(
                    "引用不可用：{reason}",
                    "Reference unavailable: {reason}",
                ),
            StringKey.LIB_UNAVAILABLE_ACTION to StringsEntry("重新选择文件", "Re-select file"),
            StringKey.LIB_SELECTED to StringsEntry("已选择：{name}", "Selected: {name}"),
            // ---- OTA 队列（v2.9.0） ----
            StringKey.QUEUE_TITLE to StringsEntry("OTA 队列", "OTA queue"),
            StringKey.QUEUE_HINT to
                StringsEntry(
                    "多台设备逐台串行升级；队列只在内存中，App 退出后中断。",
                    "Upgrades devices one after another; the queue lives only in memory and stops when the app exits.",
                ),
            StringKey.QUEUE_TARGETS to StringsEntry("选择设备", "Select devices"),
            StringKey.QUEUE_FIRMWARE to StringsEntry("固件包", "Firmware package"),
            StringKey.QUEUE_FIRMWARE_EMPTY to
                StringsEntry(
                    "固件库为空，请先在 OTA 页导入固件。",
                    "The firmware library is empty. Import firmware from the OTA page first.",
                ),
            StringKey.QUEUE_ENQUEUE to StringsEntry("加入队列", "Add to queue"),
            StringKey.QUEUE_START to StringsEntry("开始队列", "Start queue"),
            StringKey.QUEUE_PAUSE to StringsEntry("暂停队列", "Pause queue"),
            StringKey.QUEUE_RESUME to StringsEntry("继续队列", "Resume queue"),
            StringKey.QUEUE_CANCEL_CURRENT to StringsEntry("取消当前", "Cancel current"),
            StringKey.QUEUE_SKIP_FAILED_DEVICES to StringsEntry("跳过失败设备", "Skip failed devices"),
            StringKey.QUEUE_REMOVE to StringsEntry("移除", "Remove"),
            StringKey.QUEUE_EMPTY to
                StringsEntry(
                    "队列为空，请选择设备与固件包后加入。",
                    "The queue is empty. Select devices and a firmware package to add them.",
                ),
            StringKey.QUEUE_STATUS_QUEUED to StringsEntry("排队中", "Queued"),
            StringKey.QUEUE_STATUS_RUNNING to StringsEntry("升级中", "Upgrading"),
            StringKey.QUEUE_STATUS_PAUSED to StringsEntry("已暂停", "Paused"),
            StringKey.QUEUE_STATUS_SUCCEEDED to StringsEntry("成功", "Succeeded"),
            StringKey.QUEUE_STATUS_FAILURE to StringsEntry("失败", "Failed"),
            StringKey.QUEUE_STATUS_SKIPPED to StringsEntry("已跳过", "Skipped"),
            StringKey.QUEUE_STATUS_ABORTED to StringsEntry("已取消", "Aborted"),
            StringKey.QUEUE_STATUS_REMOVED to StringsEntry("已移除", "Removed"),
            StringKey.QUEUE_STAGE_PRECHECK to StringsEntry("预检", "Precheck"),
            StringKey.QUEUE_STAGE_TRANSFER to StringsEntry("传输", "Transfer"),
            StringKey.QUEUE_STAGE_VERIFY to StringsEntry("校验", "Verify"),
            StringKey.QUEUE_STAGE_DONE to StringsEntry("完成", "Done"),
            StringKey.QUEUE_SUMMARY_TITLE to StringsEntry("队列汇总", "Queue summary"),
            StringKey.QUEUE_SUMMARY_COUNTS to
                StringsEntry(
                    "共 {total} 台：成功 {succeeded}，失败 {failed}，跳过 {skipped}，取消 {aborted}，移除 {removed}，待执行 {pending}",
                    "{total} devices: {succeeded} succeeded, {failed} failed, {skipped} skipped, {aborted} aborted, {removed} removed, {pending} pending",
                ),
            StringKey.QUEUE_CHECKPOINT_RETAINED to
                StringsEntry(
                    "保留断点：{count} 台",
                    "Checkpoints kept: {count}",
                ),
            StringKey.QUEUE_MEMORY_ONLY to
                StringsEntry(
                    "队列状态只在内存与必要断点中保存，不提供后台续跑；退出 App 会中断队列。",
                    "Queue state is kept only in memory and checkpoints; there is no background continuation, and exiting the app stops the queue.",
                ),
            StringKey.QUEUE_PAUSE_REQUESTED to
                StringsEntry(
                    "已请求暂停，当前设备会在分片边界停在安全点。",
                    "Pause requested; the current device stops at a safe point between chunks.",
                ),
            StringKey.QUEUE_PAUSED_SAFE_POINT to
                StringsEntry(
                    "已在安全点暂停，可继续或取消当前。",
                    "Paused at a safe point; resume or cancel the current device.",
                ),
            StringKey.QUEUE_DUPLICATE to
                StringsEntry(
                    "该设备已在队列中。",
                    "This device is already queued.",
                ),
            StringKey.QUEUE_DEVICE_MISSING to
                StringsEntry(
                    "设备连接已不存在",
                    "The device connection no longer exists",
                ),
            StringKey.QUEUE_DEVICE_DISCONNECTED to
                StringsEntry(
                    "设备已断开",
                    "Device disconnected",
                ),
            StringKey.QUEUE_PROGRESS to StringsEntry("{stage} {percent}%", "{stage} {percent}%"),
            StringKey.QUEUE_CURRENT to StringsEntry("当前：{device}", "Current: {device}"),
            // ---- 界面四态与无障碍（v3.1.0） ----
            StringKey.STATE_EMPTY_TITLE to StringsEntry("暂无内容", "Nothing here yet"),
            StringKey.STATE_EMPTY_BODY to
                StringsEntry(
                    "当前没有可显示的数据。",
                    "There is no data to show right now.",
                ),
            StringKey.STATE_LOADING_TITLE to StringsEntry("正在加载…", "Loading…"),
            StringKey.STATE_LOADING_BODY to
                StringsEntry(
                    "请稍候，数据正在读取。",
                    "Please wait while the data is being read.",
                ),
            StringKey.STATE_ERROR_TITLE to StringsEntry("加载失败", "Couldn't load"),
            StringKey.STATE_ERROR_BODY to
                StringsEntry(
                    "读取数据时出错：{detail}",
                    "Something went wrong while loading: {detail}",
                ),
            StringKey.STATE_DISABLED_TITLE to StringsEntry("当前不可用", "Unavailable"),
            StringKey.STATE_DISABLED_NOT_CONNECTED to
                StringsEntry(
                    "设备未连接，相关操作暂不可用。",
                    "The device is not connected, so these actions are unavailable.",
                ),
            StringKey.STATE_DISABLED_READ_ONLY to
                StringsEntry(
                    "当前为只读模式，写操作暂不可用。",
                    "Read-only mode: write actions are unavailable.",
                ),
            StringKey.STATE_DISABLED_BUSY to
                StringsEntry(
                    "正在执行其他操作，请稍候。",
                    "Another operation is running. Please wait.",
                ),
            StringKey.STATE_UNAVAILABLE_HINT to
                StringsEntry(
                    "此操作在当前状态下不可用。",
                    "This action is unavailable in the current state.",
                ),
        )

    /** 查表；缺失 key 直接抛出，避免静默显示空文案。 */
    fun get(
        language: ResolvedLanguage,
        key: StringKey,
    ): String {
        val entry = entries[key] ?: error("缺少文案：$key")
        return when (language) {
            ResolvedLanguage.CHINESE -> entry.zh
            ResolvedLanguage.ENGLISH -> entry.en
        }
    }

    /** 替换 `{name}` 占位符。 */
    fun format(
        language: ResolvedLanguage,
        key: StringKey,
        args: Map<String, String> = emptyMap(),
    ): String {
        var text = get(language, key)
        args.forEach { (name, value) ->
            text = text.replace("{$name}", value)
        }
        return text
    }

    /** 供测试与检查使用：全部已登记条目。 */
    fun registeredKeys(): Set<StringKey> = entries.keys

    fun entry(key: StringKey): StringsEntry? = entries[key]
}

/** 解析后的语言，决定主题外的所有文案与格式。 */
enum class ResolvedLanguage {
    CHINESE,
    ENGLISH,
}

/** 语言偏好：跟随系统 / 中文 / English。 */
enum class AppLanguageMode {
    SYSTEM,
    CHINESE,
    ENGLISH,
}

/**
 * 语言解析（纯函数）。
 *
 * [systemLanguageTag] 使用 BCP-47 语言标签（例如 `zh-Hans-CN`、`en-US`）；
 * 以 `zh` 开头视为中文，其余一律英文，避免系统返回空值时出现未翻译界面。
 */
fun resolveLanguage(
    mode: AppLanguageMode,
    systemLanguageTag: String?,
): ResolvedLanguage =
    when (mode) {
        AppLanguageMode.CHINESE -> ResolvedLanguage.CHINESE
        AppLanguageMode.ENGLISH -> ResolvedLanguage.ENGLISH
        AppLanguageMode.SYSTEM ->
            if (systemLanguageTag?.lowercase()?.startsWith("zh") == true) {
                ResolvedLanguage.CHINESE
            } else {
                ResolvedLanguage.ENGLISH
            }
    }

/** 文案解析器，UI 只依赖这个轻量对象。 */
class StringResolver(
    val language: ResolvedLanguage,
) {
    fun t(key: StringKey): String = AppStrings.get(language, key)

    fun t(
        key: StringKey,
        vararg args: Pair<String, String>,
    ): String = AppStrings.format(language, key, args.toMap())
}

/** 当前语言；默认中文，由根界面按偏好覆盖后立即重组。 */
val LocalStrings = staticCompositionLocalOf { StringResolver(ResolvedLanguage.CHINESE) }
