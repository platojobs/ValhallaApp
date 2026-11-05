package com.valhalla.feature.i18n

import androidx.compose.runtime.staticCompositionLocalOf

/** 单条文案的中英两个版本。 */
data class StringsEntry(val zh: String, val en: String)

/**
 * 应用文案表（唯一来源）。
 *
 * 采用 commonMain 纯 Kotlin 资源而不是平台 XML：语言切换需要立即重组生效，
 * 且 key 完整性、占位符一致性可以在 commonTest 中直接断言。
 */
object AppStrings {

    private val entries: Map<StringKey, StringsEntry> = mapOf(
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
        StringKey.TECHNICAL_DETAIL to StringsEntry(
            "技术详情：{detail}",
            "Technical details: {detail}",
        ),

        // ---- 扫描 ----
        StringKey.SCAN_TITLE to StringsEntry("设备扫描", "Device scan"),
        StringKey.SCAN_HINT to StringsEntry(
            "确保设备已上电并处于广播模式。扫描需要蓝牙权限：Android 12 及以上使用「附近的设备」，旧版本使用定位权限；iOS 使用蓝牙授权。",
            "Make sure the device is powered on and advertising. Scanning needs Bluetooth permission: Android 12+ uses Nearby devices, older versions use location; iOS uses Bluetooth authorization.",
        ),
        StringKey.START_SCAN to StringsEntry("开始扫描", "Start scan"),
        StringKey.STOP_SCAN to StringsEntry("停止", "Stop"),
        StringKey.SCANNING to StringsEntry("正在扫描附近的设备…", "Scanning for nearby devices…"),
        StringKey.SCAN_CONNECTING to StringsEntry("正在连接 {device}…", "Connecting to {device}…"),
        StringKey.UNNAMED_DEVICE to StringsEntry("未命名设备", "Unnamed device"),
        StringKey.DEVICE_RSSI to StringsEntry("{id} · 信号 {rssi} dBm", "{id} · RSSI {rssi} dBm"),
        StringKey.SCAN_EMPTY_SCANNING to StringsEntry("正在扫描附近的设备…", "Scanning for nearby devices…"),
        StringKey.SCAN_EMPTY_TIMEOUT to StringsEntry(
            "扫描 12 秒仍无设备：请确认设备已上电、处于广播模式并靠近手机，然后重新扫描。",
            "No device after 12 seconds: check that the device is powered on, advertising, and close to the phone, then scan again.",
        ),
        StringKey.SCAN_EMPTY_NO_DEVICES to StringsEntry(
            "暂无设备：请确认设备已上电并处于广播模式，然后重新扫描。",
            "No devices: check that the device is powered on and advertising, then scan again.",
        ),
        StringKey.SCAN_EMPTY_NO_PERMISSION to StringsEntry(
            "没有蓝牙权限，无法发现设备：请在系统弹窗中授予权限后重试。",
            "Bluetooth permission is missing, so no devices can be found: grant it in the system prompt and retry.",
        ),
        StringKey.SCAN_EMPTY_PERMISSION_PERMANENTLY_DENIED to StringsEntry(
            "蓝牙权限已被永久拒绝：请前往系统设置开启，返回应用后再扫描。",
            "Bluetooth permission is permanently denied: enable it in system settings, then return and scan.",
        ),
        StringKey.SCAN_EMPTY_BLUETOOTH_OFF to StringsEntry(
            "蓝牙已关闭，无法扫描：请开启蓝牙后重试。",
            "Bluetooth is off, so scanning is unavailable: turn Bluetooth on and retry.",
        ),
        StringKey.SCAN_EMPTY_BLUETOOTH_RESETTING to StringsEntry(
            "蓝牙正在重置：请等待几秒后重试。",
            "Bluetooth is resetting: wait a few seconds and retry.",
        ),
        StringKey.SCAN_EMPTY_UNSUPPORTED to StringsEntry(
            "当前设备不支持蓝牙低功耗，无法扫描。",
            "This device does not support Bluetooth Low Energy, so scanning is unavailable.",
        ),
        StringKey.SCAN_EMPTY_UNKNOWN to StringsEntry(
            "暂时无法获取蓝牙状态：请稍后重试。",
            "Bluetooth state is unavailable: retry shortly.",
        ),
        StringKey.SCAN_EMPTY_ERROR to StringsEntry(
            "扫描失败：请检查蓝牙状态后重试。详情：{detail}",
            "Scan failed: check Bluetooth state and retry. Details: {detail}",
        ),
        StringKey.BLUETOOTH_UNAVAILABLE to StringsEntry(
            "蓝牙当前不可用：请检查权限与蓝牙开关后重试。",
            "Bluetooth is unavailable: check permission and the Bluetooth switch, then retry.",
        ),

        // ---- 权限/蓝牙引导 ----
        StringKey.GUIDANCE_NEED_PERMISSION_TITLE to StringsEntry("需要蓝牙权限", "Bluetooth permission required"),
        StringKey.GUIDANCE_NEED_PERMISSION_MESSAGE to StringsEntry(
            "扫描并连接设备需要蓝牙权限。Android 12 及以上使用「附近的设备」权限，旧版本使用定位权限。",
            "Scanning and connecting need Bluetooth permission. Android 12+ uses Nearby devices; older versions use location.",
        ),
        StringKey.GUIDANCE_PERMISSION_DENIED_TITLE to StringsEntry("蓝牙权限被拒绝", "Bluetooth permission denied"),
        StringKey.GUIDANCE_PERMISSION_DENIED_MESSAGE to StringsEntry(
            "没有蓝牙权限无法扫描设备。可再次授权；若系统不再弹窗，请前往系统设置手动开启。",
            "Without Bluetooth permission no devices can be found. Grant it again, or enable it in system settings if the prompt no longer appears.",
        ),
        StringKey.GUIDANCE_PERMISSION_PERMANENT_TITLE to StringsEntry(
            "蓝牙权限已被永久拒绝",
            "Bluetooth permission permanently denied",
        ),
        StringKey.GUIDANCE_PERMISSION_PERMANENT_MESSAGE to StringsEntry(
            "系统不会再弹出授权窗口。请前往「设置 → 应用 → Valhalla → 权限」开启蓝牙/附近设备权限。",
            "The system will not show the prompt again. Open Settings → Apps → Valhalla → Permissions and enable Bluetooth/Nearby devices.",
        ),
        StringKey.GUIDANCE_BLUETOOTH_OFF_TITLE to StringsEntry("蓝牙已关闭", "Bluetooth is off"),
        StringKey.GUIDANCE_BLUETOOTH_OFF_MESSAGE to StringsEntry(
            "请开启蓝牙后重试；开启后扫描与连接会自动恢复。",
            "Turn Bluetooth on and retry; scanning and connections resume automatically.",
        ),
        StringKey.GUIDANCE_BLUETOOTH_RESETTING_TITLE to StringsEntry("蓝牙正在重置", "Bluetooth is resetting"),
        StringKey.GUIDANCE_BLUETOOTH_RESETTING_MESSAGE to StringsEntry(
            "系统正在重启蓝牙服务，请稍候再试。",
            "The system is restarting the Bluetooth service. Wait a moment and retry.",
        ),
        StringKey.GUIDANCE_UNSUPPORTED_TITLE to StringsEntry(
            "设备不支持蓝牙低功耗",
            "Bluetooth Low Energy unsupported",
        ),
        StringKey.GUIDANCE_UNSUPPORTED_MESSAGE to StringsEntry(
            "当前设备没有可用的 BLE 适配器，无法扫描或连接 Valhalla 硬件。",
            "This device has no usable BLE adapter, so Valhalla hardware cannot be scanned or connected.",
        ),
        StringKey.GUIDANCE_UNKNOWN_TITLE to StringsEntry("蓝牙状态未知", "Bluetooth state unknown"),
        StringKey.GUIDANCE_UNKNOWN_MESSAGE to StringsEntry(
            "暂时无法获取蓝牙状态，请稍后重试。",
            "Bluetooth state cannot be read right now. Retry shortly.",
        ),
        StringKey.ACTION_GRANT_PERMISSION to StringsEntry("授予权限", "Grant permission"),
        StringKey.ACTION_OPEN_SETTINGS to StringsEntry("去系统设置", "Open settings"),
        StringKey.ACTION_ENABLE_BLUETOOTH to StringsEntry("开启蓝牙", "Turn on Bluetooth"),
        StringKey.ACTION_RESCAN to StringsEntry("返回扫描", "Back to scan"),

        // ---- 断连 ----
        StringKey.DISCONNECT_USER_TITLE to StringsEntry("已断开连接", "Disconnected"),
        StringKey.DISCONNECT_USER_MESSAGE to StringsEntry(
            "你主动断开了与设备的连接。可以返回扫描页重新连接。",
            "You disconnected from the device. Return to the scan page to reconnect.",
        ),
        StringKey.DISCONNECT_PERMISSION_TITLE to StringsEntry("蓝牙权限丢失", "Bluetooth permission lost"),
        StringKey.DISCONNECT_PERMISSION_MESSAGE to StringsEntry(
            "系统收回了蓝牙权限，无法继续与设备通信。请在设置中重新授权后连接。",
            "The system revoked Bluetooth permission, so communication stopped. Re-authorize in settings and reconnect.",
        ),
        StringKey.DISCONNECT_BLUETOOTH_OFF_TITLE to StringsEntry("蓝牙已关闭", "Bluetooth is off"),
        StringKey.DISCONNECT_BLUETOOTH_OFF_MESSAGE to StringsEntry(
            "蓝牙关闭导致链路中断。开启蓝牙后将自动尝试重连。",
            "Bluetooth was turned off, which broke the link. Reconnection starts automatically after Bluetooth is on.",
        ),
        StringKey.DISCONNECT_RESETTING_TITLE to StringsEntry("蓝牙正在重置", "Bluetooth is resetting"),
        StringKey.DISCONNECT_RESETTING_MESSAGE to StringsEntry(
            "系统正在重启蓝牙服务，链路暂时不可用，稍后会自动重连。",
            "The system is restarting Bluetooth, so the link is temporarily unavailable. Reconnection follows automatically.",
        ),
        StringKey.DISCONNECT_TIMEOUT_TITLE to StringsEntry("连接超时", "Connection timed out"),
        StringKey.DISCONNECT_TIMEOUT_MESSAGE to StringsEntry(
            "在限定时间内未能完成连接。请确认设备已上电并靠近手机后重试。",
            "The connection did not complete in time. Confirm the device is powered on and nearby, then retry.",
        ),
        StringKey.DISCONNECT_DEVICE_TITLE to StringsEntry("设备已断开连接", "Device disconnected"),
        StringKey.DISCONNECT_DEVICE_MESSAGE to StringsEntry(
            "设备侧断开了链路，可能已关机、超出范围或正在重启。靠近设备后可重新连接。",
            "The device ended the link, possibly because it powered off, moved out of range, or restarted. Move closer and reconnect.",
        ),
        StringKey.DISCONNECT_CONNECT_FAILED_TITLE to StringsEntry("连接失败", "Connection failed"),
        StringKey.DISCONNECT_CONNECT_FAILED_MESSAGE to StringsEntry(
            "与设备建立链路失败。请靠近设备、确认设备可连接后重试。",
            "The link could not be established. Move closer, confirm the device is connectable, and retry.",
        ),
        StringKey.DISCONNECT_UNKNOWN_TITLE to StringsEntry("连接已断开", "Disconnected"),
        StringKey.DISCONNECT_UNKNOWN_MESSAGE to StringsEntry(
            "未知原因导致链路中断。可尝试重新连接。",
            "The link dropped for an unknown reason. Try reconnecting.",
        ),

        // ---- 重连 ----
        StringKey.RECONNECT_WAITING to StringsEntry(
            "将在 {seconds} 秒后进行第 {attempt} 次自动重连",
            "Automatic reconnect attempt {attempt} in {seconds}s",
        ),
        StringKey.RECONNECT_CONNECTING to StringsEntry(
            "正在执行第 {attempt} 次自动重连…",
            "Running automatic reconnect attempt {attempt}…",
        ),
        StringKey.RECONNECT_PAUSED_BLUETOOTH_OFF to StringsEntry(
            "蓝牙已关闭，自动重连已暂停；开启蓝牙后继续。",
            "Bluetooth is off, so automatic reconnect is paused. It resumes when Bluetooth is back on.",
        ),
        StringKey.RECONNECT_PAUSED to StringsEntry("自动重连已暂停。", "Automatic reconnect is paused."),
        StringKey.RECONNECT_EXHAUSTED to StringsEntry(
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
        StringKey.MODEL_LINE to StringsEntry(
            "型号：{vendor} {model} · 序列号 {serial}",
            "Model: {vendor} {model} · S/N {serial}",
        ),
        StringKey.AUTHENTICATE to StringsEntry("鉴权", "Authenticate"),
        StringKey.AUTH_MODE_LABEL to StringsEntry("鉴权模式", "Auth mode"),
        StringKey.AUTH_MODE_TOKEN to StringsEntry("预共享密钥", "Pre-shared key"),
        StringKey.AUTH_MODE_SIGNED to StringsEntry("签名挑战", "Signed challenge"),
        StringKey.AUTH_CREDENTIAL_LABEL to StringsEntry("密钥 / 令牌", "Key / token"),
        StringKey.AUTH_SECRET_HINT to StringsEntry(
            "直接以预共享密钥/令牌鉴权（TOKEN 模式）。",
            "Authenticate with the pre-shared key or token (TOKEN mode).",
        ),
        StringKey.AUTH_SIGNED_HINT to StringsEntry(
            "先取设备挑战，再用 HMAC-SHA256 对挑战、随机数与设备摘要签名（SIGNED 模式）。",
            "Fetch the device challenge, then HMAC-SHA256 sign challenge, nonce and device digest (SIGNED mode).",
        ),
        StringKey.REFRESH_INFO to StringsEntry("刷新信息", "Refresh info"),
        StringKey.ENTER_OTA to StringsEntry("进入 OTA 升级", "Open OTA update"),
        StringKey.OPEN_LOGS to StringsEntry("报文日志（TX/RX）", "Packet logs (TX/RX)"),
        StringKey.DEVICE_LOGS to StringsEntry("设备日志", "Device logs"),
        StringKey.PROFILE_TITLE to StringsEntry("设备档案", "Device profile"),
        StringKey.PROFILE_LAST_CONNECTED to StringsEntry("最后连接：{time}", "Last connected: {time}"),
        StringKey.PROFILE_SUMMARY to StringsEntry(
            "最近固件：{version} · 电量：{percent}",
            "Last firmware: {version} · Battery: {percent}",
        ),
        StringKey.PROFILE_LAST_OTA to StringsEntry("最近 OTA：{outcome}", "Last OTA: {outcome}"),
        StringKey.PROFILE_OTA_RECORD to StringsEntry(
            "{outcome} · 固件 {version} · 耗时 {duration} ms",
            "{outcome} · firmware {version} · {duration} ms",
        ),
        StringKey.ERROR_CODE_LABEL to StringsEntry("错误码：{code}", "Error code: {code}"),
        StringKey.AUTH_FAILED to StringsEntry(
            "鉴权失败：{detail}。请确认设备处于配对模式后重试。",
            "Authentication failed: {detail}. Confirm the device is in pairing mode and retry.",
        ),
        StringKey.AUTH_CHALLENGE_FAILED to StringsEntry(
            "获取鉴权挑战失败：{detail}。请重新连接设备后重试。",
            "Failed to obtain the authentication challenge: {detail}. Reconnect the device and retry.",
        ),
        StringKey.INFO_REFRESH_FAILED to StringsEntry(
            "设备信息读取失败：{detail}。请确认设备在线后刷新。",
            "Failed to read device info: {detail}. Confirm the device is online and refresh.",
        ),
        StringKey.DEVICE_CONFIG_READ_FAILED to StringsEntry(
            "读取配置失败：{detail}。请确认设备在线后刷新。",
            "Failed to read the setting: {detail}. Confirm the device is online and refresh.",
        ),
        StringKey.DEVICE_CONFIG_WRITE_FAILED to StringsEntry(
            "写入配置失败：{detail}。请确认设备在线后重试。",
            "Failed to write the setting: {detail}. Confirm the device is online and retry.",
        ),
        StringKey.FAILURE_DEVICE_REJECTED to StringsEntry(
            "设备拒绝请求（{status}）。请确认固件与设备匹配后重试。",
            "The device rejected the request ({status}). Confirm the firmware matches the device and retry.",
        ),
        StringKey.FAILURE_STATUS to StringsEntry(
            "状态码 {code}",
            "status {code}",
        ),
        StringKey.FAILURE_STATUS_DETAIL to StringsEntry(
            "状态码 {code}：{detail}",
            "status {code}: {detail}",
        ),
        StringKey.FAILURE_TIMEOUT to StringsEntry(
            "设备无响应（已重试 {attempts} 次）。请确认设备在附近且电量充足后重试。",
            "The device did not respond ({attempts} attempts). Confirm it is nearby and charged, then retry.",
        ),
        StringKey.FAILURE_TRANSPORT to StringsEntry(
            "传输中断：{detail}。请重新连接设备后重试。",
            "Transport interrupted: {detail}. Reconnect the device and retry.",
        ),
        StringKey.FAILURE_INVALID_RESPONSE to StringsEntry(
            "设备应答无法解析：{detail}。请重试或重新连接设备。",
            "The device response could not be parsed: {detail}. Retry or reconnect the device.",
        ),
        StringKey.FAILURE_UNKNOWN to StringsEntry(
            "未知错误：{detail}。请重试；若持续出现请导出日志反馈。",
            "Unknown error: {detail}. Retry; if it persists, export the logs and report it.",
        ),

        // ---- 配置 ----
        StringKey.CONFIG_TITLE to StringsEntry("设备配置", "Device configuration"),
        StringKey.CONFIG_RELOAD to StringsEntry("重新读取", "Reload"),
        StringKey.CONFIG_HINT to StringsEntry(
            "配置键能力按固件版本与型号判定；不支持的键已隐藏，避免下发后设备返回参数非法。",
            "Available keys are determined by firmware and model; unsupported keys are hidden to avoid invalid-parameter replies.",
        ),
        StringKey.CONFIG_HIDDEN_UNKNOWN_TARGET to StringsEntry(
            "已隐藏 {count} 项配置：固件版本或型号未知。请先刷新设备信息，再判定可用性。",
            "{count} settings hidden: firmware or model is unknown. Refresh device info to determine availability.",
        ),
        StringKey.CONFIG_HIDDEN_UNSUPPORTED to StringsEntry(
            "已隐藏 {count} 项配置：当前设备（固件 {firmware}，型号 {model}）不支持。",
            "{count} settings hidden: unsupported on this device (firmware {firmware}, model {model}).",
        ),
        StringKey.REFRESH_DEVICE_INFO to StringsEntry("刷新设备信息", "Refresh device info"),
        StringKey.CONFIG_SAVED_FIELD to StringsEntry("已保存 {field}", "Saved {field}"),
        StringKey.CONFIG_UNKNOWN_KEY to StringsEntry(
            "无法保存：未知配置项「{key}」。请刷新配置列表后重试。",
            "Cannot save: unknown setting \"{key}\". Reload the list and retry.",
        ),
        StringKey.CONFIG_UNSUPPORTED_FIELD to StringsEntry(
            "无法保存「{field}」：当前固件或型号不支持该配置项。请刷新设备信息后重试。",
            "Cannot save \"{field}\": unsupported on this firmware or model. Refresh device info and retry.",
        ),
        StringKey.CONFIG_READ_ONLY_FIELD to StringsEntry(
            "无法保存「{field}」：该配置项只读。",
            "Cannot save \"{field}\": it is read-only.",
        ),
        StringKey.CONFIG_SAVING_BUSY to StringsEntry(
            "请稍候：正在保存 {field}，完成后再提交。",
            "Wait: {field} is being saved. Submit again when it finishes.",
        ),
        StringKey.CONFIG_NO_KEYS to StringsEntry(
            "当前没有可读取的配置项：固件或型号未知。请刷新设备信息后重试。",
            "No readable settings: firmware or model is unknown. Refresh device info and retry.",
        ),
        StringKey.CONFIG_READ_FAILED to StringsEntry(
            "读取失败：{detail}。请刷新设备信息后重试。",
            "Read failed: {detail}. Refresh device info and retry.",
        ),
        StringKey.CONFIG_LOAD_FAILED to StringsEntry(
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
        StringKey.OTA_DEVICE_LINE to StringsEntry(
            "设备版本：{version} · 型号：{model}",
            "Device firmware: {version} · Model: {model}",
        ),
        StringKey.SELECT_FIRMWARE to StringsEntry("选择固件", "Select firmware"),
        StringKey.RESELECT_FIRMWARE to StringsEntry("重新选择固件", "Select a different firmware"),
        StringKey.OPENING_PICKER to StringsEntry("正在打开文件选择器…", "Opening the file picker…"),
        StringKey.PRECHECK_PROGRESS to StringsEntry(
            "正在预检固件… {percent}%",
            "Prechecking firmware… {percent}%",
        ),
        StringKey.CANCEL_PRECHECK to StringsEntry("取消预检", "Cancel precheck"),
        StringKey.OTA_IDLE to StringsEntry("等待开始", "Waiting to start"),
        StringKey.OTA_PREPARING to StringsEntry(
            "准备中：{bytes} 字节，CRC={crc}{session}",
            "Preparing: {bytes} bytes, CRC={crc}{session}",
        ),
        StringKey.OTA_TRANSFERRING to StringsEntry(
            "{stage}：已发送 {sent}/{total} 字节（{percent}%）{resume}",
            "{stage}: {sent}/{total} bytes sent ({percent}%){resume}",
        ),
        StringKey.OTA_RESUME_NOTE to StringsEntry("（从 {offset} 续传）", " (resumed from {offset})"),
        StringKey.OTA_RETRANSMISSIONS to StringsEntry(
            "分片重传 {count} 次",
            "Chunk retransmissions: {count}",
        ),
        StringKey.OTA_VERIFYING to StringsEntry(
            "校验中：设备正在核对 CRC={crc}",
            "Verifying: the device is checking CRC={crc}",
        ),
        StringKey.OTA_SUCCEEDED to StringsEntry(
            "升级完成：{chunks} 片，CRC={crc}",
            "Update complete: {chunks} chunks, CRC={crc}",
        ),
        StringKey.OTA_REBOOT_REQUIRED to StringsEntry("，设备将重启", ", the device will restart"),
        StringKey.OTA_ABORTED to StringsEntry(
            "升级已中止：{reason}（已发送 {sent}/{total} 字节）",
            "Update aborted: {reason} ({sent}/{total} bytes sent)",
        ),
        StringKey.OTA_FAILED to StringsEntry(
            "升级失败：{reason}。请按原因处理后重试。",
            "Update failed: {reason}. Resolve the cause and retry.",
        ),
        StringKey.OTA_SENT_BYTES to StringsEntry(
            "已发送 {sent}/{total} 字节",
            "{sent}/{total} bytes sent",
        ),
        StringKey.OTA_RESUME_SAVED to StringsEntry(
            "已保存断点：{sent}/{total} 字节（会话 {session}）",
            "Checkpoint saved: {sent}/{total} bytes (session {session})",
        ),
        StringKey.OTA_SESSION_SUFFIX to StringsEntry(
            "，会话 {id}",
            ", session {id}",
        ),
        StringKey.START_OTA to StringsEntry("开始升级", "Start update"),
        StringKey.RESUME_OTA to StringsEntry("继续上次升级", "Resume last update"),
        StringKey.RESUME_OTA_WITH_PROGRESS to StringsEntry(
            "继续上次升级（{sent}/{total}）",
            "Resume last update ({sent}/{total})",
        ),
        StringKey.CLEAR_RESUME to StringsEntry("清除断点", "Clear checkpoint"),
        StringKey.ABORT_OTA to StringsEntry("中止升级", "Abort update"),
        StringKey.RESET_STATE to StringsEntry("重置状态", "Reset state"),
        StringKey.FIRMWARE_VERSION_LINE to StringsEntry(
            "版本 {version} · payload {bytes}",
            "Version {version} · payload {bytes}",
        ),
        StringKey.FIRMWARE_CRC32_LINE to StringsEntry(
            "CRC32 声明 {declared} · 实际 {computed}",
            "CRC32 declared {declared} · computed {computed}",
        ),
        StringKey.FIRMWARE_CRC16_LINE to StringsEntry("CRC16 {value}", "CRC16 {value}"),
        StringKey.FIRMWARE_RESUME_READY to StringsEntry(
            "可续传：从 {offset} 继续（已传输 {sent}/{total} 字节）",
            "Resumable from {offset} ({sent}/{total} bytes transferred)",
        ),
        StringKey.FIRMWARE_NOT_PRECHECKED to StringsEntry("尚未预检", "Not prechecked yet"),
        StringKey.FIRMWARE_PRECHECK_PASSED to StringsEntry(
            "预检通过，可以开始升级。",
            "Precheck passed; the update can start.",
        ),
        StringKey.FIRMWARE_PRECHECK_FAILED to StringsEntry(
            "预检未通过，OTA 不可启动。请修正固件后重试。",
            "Precheck failed, so OTA cannot start. Fix the firmware and retry.",
        ),
        StringKey.FIRMWARE_LOAD_FAILED to StringsEntry(
            "固件加载失败：{detail}。请重新选择固件文件。",
            "Firmware load failed: {detail}. Select the firmware file again.",
        ),
        StringKey.FIRMWARE_FILE_READ_FAILED to StringsEntry(
            "读取固件文件失败：{detail}。请确认文件仍可访问后重新选择。",
            "Failed to read the firmware file: {detail}. Confirm the file is still accessible and select it again.",
        ),
        StringKey.RESUME_INVALID to StringsEntry(
            "本地断点已失效，已改为从头升级。",
            "The local resume checkpoint is invalid, so the update restarts from the beginning.",
        ),
        StringKey.RESUME_MISMATCH to StringsEntry(
            "断点与当前固件不匹配（版本/总长/CRC32），已改为从头升级。",
            "The checkpoint does not match this firmware (version, total size, or CRC32), so the update restarts from the beginning.",
        ),
        StringKey.RESUME_PRECHECK_BLOCKED to StringsEntry(
            "当前固件预检未通过，暂不能续传。请重新预检或从头升级。",
            "Precheck did not pass for this firmware, so resuming is unavailable. Re-run the precheck or start over.",
        ),
        StringKey.RESUME_CRC16_MISSING to StringsEntry(
            "无法计算固件 CRC16，已禁用续传。请重新预检或从头升级。",
            "The firmware CRC16 could not be computed, so resuming is disabled. Re-run the precheck or start over.",
        ),
        StringKey.RESUME_CRC16_MISMATCH to StringsEntry(
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
        StringKey.OTA_REASON_CRC_MISMATCH to StringsEntry(
            "设备回报 CRC 不一致。请重新选择固件并重新预检。",
            "The device reported a CRC mismatch. Select the firmware again and re-run the precheck.",
        ),
        StringKey.OTA_REASON_DISCONNECTED to StringsEntry(
            "链路已断开。请重新连接设备后使用「继续上次升级」。",
            "The link is down. Reconnect the device, then use \"Resume last update\".",
        ),
        StringKey.OTA_REASON_SESSION_EXISTS to StringsEntry(
            "已有升级会话在进行。请先中止或等待其结束后再试。",
            "Another update session is already running. Abort it or wait for it to finish.",
        ),
        StringKey.OTA_REASON_EMPTY_FIRMWARE to StringsEntry(
            "固件内容为空。请重新选择有效的固件文件。",
            "The firmware is empty. Select a valid firmware file.",
        ),
        StringKey.OTA_REASON_INVALID_LENGTH to StringsEntry(
            "固件长度非法（{length}）。请重新选择有效的固件文件。",
            "Invalid firmware length ({length}). Select a valid firmware file.",
        ),
        StringKey.OTA_REASON_FIRMWARE_READ to StringsEntry(
            "固件读取失败（offset={offset}, length={length}）。请重新选择固件文件。",
            "Firmware read failed (offset={offset}, length={length}). Select the firmware file again.",
        ),
        StringKey.OTA_REASON_READ_FIRMWARE to StringsEntry(
            "读取固件失败（offset={offset}, length={length}）。请重新选择固件文件。",
            "Reading the firmware failed (offset={offset}, length={length}). Select the firmware file again.",
        ),
        StringKey.OTA_REASON_TIMEOUT to StringsEntry(
            "设备无响应（尝试 {attempts} 次后超时）。请确认设备在附近且电量充足后重试。",
            "The device did not respond (timed out after {attempts} attempts). Confirm it is nearby and charged, then retry.",
        ),
        StringKey.OTA_REASON_TRANSPORT to StringsEntry(
            "链路传输失败（尝试 {attempts} 次）。请重新连接设备后重试。",
            "Link transfer failed ({attempts} attempts). Reconnect the device and retry.",
        ),
        StringKey.OTA_REASON_OFFSET_TOO_LARGE to StringsEntry(
            "设备确认的续传偏移超出范围（{offset} ≥ {total}）。请清除断点后从头升级。",
            "The device accepted an out-of-range resume offset ({offset} ≥ {total}). Clear the checkpoint and start over.",
        ),
        StringKey.OTA_REASON_OFFSET_UNEXPECTED to StringsEntry(
            "未请求续传，但设备返回偏移 {offset}。请清除断点或重置状态后重试。",
            "Resume was not requested, but the device returned offset {offset}. Clear the checkpoint or reset and retry.",
        ),
        StringKey.OTA_REASON_OFFSET_GREATER_THAN_REQUESTED to StringsEntry(
            "设备确认偏移 {accepted} 大于请求偏移 {requested}。请清除断点后从头升级。",
            "The device accepted offset {accepted}, which exceeds the requested {requested}. Clear the checkpoint and start over.",
        ),
        StringKey.OTA_REASON_CANCELLED to StringsEntry(
            "升级已取消。可重新开始或继续上次升级。",
            "The update was cancelled. Start again or resume the last update.",
        ),
        StringKey.OTA_ABORT_TIMEOUT to StringsEntry(
            "设备超时，升级已中止。请确认设备在附近且电量充足后重试。",
            "The device timed out, so the update was aborted. Confirm it is nearby and charged, then retry.",
        ),
        StringKey.OTA_ABORT_VERIFY_FAILED to StringsEntry(
            "设备校验失败，升级已中止。请重新选择固件并重试。",
            "The device failed verification, so the update was aborted. Select the firmware again and retry.",
        ),
        StringKey.OTA_ABORT_TRANSPORT to StringsEntry(
            "链路异常，升级已中止。请重新连接设备后继续升级。",
            "The link failed, so the update was aborted. Reconnect the device and resume.",
        ),
        StringKey.OTA_ABORT_DEVICE_REJECTED to StringsEntry(
            "设备拒绝升级，已中止。请确认固件与设备型号和版本匹配。",
            "The device rejected the update, so it was aborted. Confirm the firmware matches the model and version.",
        ),
        StringKey.OTA_ABORT_USER to StringsEntry(
            "你取消了升级。可随时重新开始或继续上次升级。",
            "You cancelled the update. Start again or resume at any time.",
        ),
        StringKey.OTA_REASON_DEVICE_REJECTED to StringsEntry(
            "设备拒绝升级请求：{detail}。请确认固件与设备匹配后重试。",
            "The device rejected the update request: {detail}. Confirm the firmware matches the device and retry.",
        ),
        StringKey.OTA_REASON_INVALID_RESPONSE to StringsEntry(
            "设备应答无法解析：{detail}。请重试；若持续出现，请记录日志反馈。",
            "The device response could not be parsed: {detail}. Retry; if it persists, export logs and report it.",
        ),
        StringKey.OTA_FALLBACK_NOT_ACCEPTED to StringsEntry(
            "设备未接受续传偏移，已从 0 开始。",
            "The device did not accept the resume offset, so the transfer restarted from 0.",
        ),
        StringKey.OTA_FALLBACK_LOWERED to StringsEntry(
            "设备将续传偏移下调到 {accepted} 字节（请求 {requested} 字节）。",
            "The device lowered the resume offset to {accepted} bytes (requested {requested}).",
        ),
        StringKey.OTA_FALLBACK_REJECTED to StringsEntry(
            "设备拒绝续传（{detail}），已回退为从头开始。",
            "The device rejected the resume ({detail}); the transfer restarted from the beginning.",
        ),

        // ---- 日志与导出 ----
        StringKey.LOGS_TITLE to StringsEntry("报文日志", "Packet logs"),
        StringKey.LOGS_SEARCH to StringsEntry(
            "搜索命令名 / 摘要 / HEX / seq",
            "Search command / summary / HEX / seq",
        ),
        StringKey.LOGS_ALL_COMMANDS to StringsEntry("全部命令", "All commands"),
        StringKey.LOGS_ERRORS_ONLY to StringsEntry("仅错误", "Errors only"),
        StringKey.LOGS_START_TIME to StringsEntry("起始时间", "Start time"),
        StringKey.LOGS_END_TIME to StringsEntry("结束时间", "End time"),
        StringKey.LOGS_TIME_PLACEHOLDER to StringsEntry("YYYY-MM-DD HH:mm:ss", "YYYY-MM-DD HH:mm:ss"),
        StringKey.LOGS_TIME_HINT to StringsEntry(
            "留空表示不限，含边界",
            "Leave empty for no bound; bounds are inclusive",
        ),
        StringKey.LOGS_TIME_RANGE_ERROR to StringsEntry(
            "起始时间不能晚于结束时间。",
            "Start time cannot be later than end time.",
        ),
        StringKey.LOGS_TIME_FORMAT_ERROR to StringsEntry(
            "时间格式无效：应为 YYYY-MM-DD HH:mm:ss。",
            "Invalid time format: use YYYY-MM-DD HH:mm:ss.",
        ),
        StringKey.LOGS_ENTRY_LIMIT to StringsEntry("条数上限", "Entry limit"),
        StringKey.LOGS_BYTES_LIMIT to StringsEntry("大小上限（KB）", "Size limit (KB)"),
        StringKey.LOGS_ENTRY_RANGE to StringsEntry(
            "允许 {min}..{max}",
            "Allowed {min}..{max}",
        ),
        StringKey.LOGS_BYTES_RANGE to StringsEntry("允许 1..{max}", "Allowed 1..{max}"),
        StringKey.LOGS_ESTIMATE_LOADING to StringsEntry("正在估算导出量…", "Estimating export size…"),
        StringKey.LOGS_ESTIMATE_INVALID_RANGE to StringsEntry(
            "时间范围无效：{detail}",
            "Invalid time range: {detail}",
        ),
        StringKey.LOGS_ESTIMATE_INVALID_LIMIT to StringsEntry(
            "上限输入无效：{detail}",
            "Invalid limit: {detail}",
        ),
        StringKey.LOGS_ESTIMATE_NO_MATCH to StringsEntry(
            "没有匹配的报文，不会导出空文件。",
            "No matching packets, so no empty file will be exported.",
        ),
        StringKey.LOGS_ESTIMATE_TRUNCATED to StringsEntry(
            "预计导出 {entries} 条 / {bytes}（匹配 {matched} 条，超出上限将截断最旧的一端）",
            "Exporting about {entries} entries / {bytes} ({matched} matched; oldest entries are truncated when over the limit)",
        ),
        StringKey.LOGS_ESTIMATE to StringsEntry(
            "预计导出 {entries} 条 / {bytes}",
            "Exporting about {entries} entries / {bytes}",
        ),
        StringKey.LOGS_EXPORTING to StringsEntry(
            "导出中 · {phase} {processed}/{total}",
            "Exporting · {phase} {processed}/{total}",
        ),
        StringKey.LOGS_EXPORT_PHASE_FILTERING to StringsEntry("筛选", "Filtering"),
        StringKey.LOGS_EXPORT_PHASE_RENDERING to StringsEntry("生成", "Rendering"),
        StringKey.LOGS_EXPORT_PHASE_ASSEMBLING to StringsEntry("拼装", "Assembling"),
        StringKey.LOGS_EXPORT_PREPARING to StringsEntry("准备", "Preparing"),
        StringKey.LOGS_DISPLAY_COUNT to StringsEntry(
            "显示 {shown} / {total} 条 · 上限 {capacity} 条，超出滚动淘汰",
            "Showing {shown} / {total} · capacity {capacity}, oldest entries are evicted",
        ),
        StringKey.LOGS_EMPTY to StringsEntry(
            "暂无报文：连接设备并触发鉴权、信息读取或 OTA 后，这里会记录 TX/RX 报文。",
            "No packets yet: connect a device and authenticate, read info, or run OTA to record TX/RX packets.",
        ),
        StringKey.LOGS_NO_MATCH to StringsEntry(
            "没有匹配当前筛选条件的报文。",
            "No packets match the current filters.",
        ),
        StringKey.LOGS_SAVE_SUCCESS to StringsEntry(
            "已保存 {name}（{entries} 条，{bytes} 字节）",
            "Saved {name} ({entries} entries, {bytes} bytes)",
        ),
        StringKey.LOGS_SHARE_SUCCESS to StringsEntry(
            "已分享 {name}（{entries} 条，{bytes} 字节）",
            "Shared {name} ({entries} entries, {bytes} bytes)",
        ),
        StringKey.LOGS_SAVE_CANCELLED to StringsEntry(
            "已取消保存，未写入文件。",
            "Save cancelled; no file was written.",
        ),
        StringKey.LOGS_EXPORT_CANCELLED to StringsEntry(
            "已取消导出，未写入文件。",
            "Export cancelled; no file was written.",
        ),
        StringKey.LOGS_SAVE_UNAVAILABLE to StringsEntry(
            "当前平台不支持保存为文件，可改用分享。",
            "This platform cannot save files; use Share instead.",
        ),
        StringKey.LOGS_SHARE_UNAVAILABLE to StringsEntry(
            "当前平台不支持系统分享。",
            "System sharing is unavailable on this platform.",
        ),
        StringKey.LOGS_SAVE_FAILED to StringsEntry(
            "保存失败：{detail}。请检查存储空间后重试。",
            "Save failed: {detail}. Check free storage and retry.",
        ),
        StringKey.LOGS_SHARE_FAILED to StringsEntry(
            "分享失败：{detail}。请重试或改用保存文件。",
            "Share failed: {detail}. Retry or save the file instead.",
        ),
        StringKey.LOGS_INVALID_LIMIT to StringsEntry(
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
        StringKey.TIME_FORMAT_PLACEHOLDER to StringsEntry(
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
        StringKey.ABOUT_BUILD_INFO to StringsEntry(
            "构建信息：Kotlin Multiplatform · Compose Multiplatform · {platform}",
            "Build: Kotlin Multiplatform · Compose Multiplatform · {platform}",
        ),
        StringKey.ABOUT_PLATFORM to StringsEntry("运行平台：{platform}", "Platform: {platform}"),
        StringKey.ABOUT_FIRMWARE_KEY_CONFIGURED to StringsEntry(
            "固件验签公钥：已配置",
            "Firmware signing key: configured",
        ),
        StringKey.ABOUT_FIRMWARE_KEY_MISSING to StringsEntry(
            "固件验签公钥：未配置（固件升级仅告警，不阻断）",
            "Firmware signing key: not configured (updates warn only)",
        ),
        StringKey.ABOUT_LICENSE_TITLE to StringsEntry("开源许可", "Open-source licenses"),
        StringKey.ABOUT_LICENSE_BODY to StringsEntry(
            "本应用使用 Kotlin、Kotlinx Coroutines、Compose Multiplatform（Apache License 2.0）以及平台蓝牙框架。完整许可见各依赖仓库的 LICENSE 文件。",
            "This app uses Kotlin, Kotlinx Coroutines, Compose Multiplatform (Apache License 2.0), and platform Bluetooth frameworks. See each dependency's LICENSE file for full terms.",
        ),

        // ---- 帮助 ----
        StringKey.HELP_TITLE to StringsEntry("帮助与常见问题", "Help & FAQ"),
        StringKey.HELP_Q_PERMISSION to StringsEntry(
            "为什么扫描不到设备 / 提示没有权限？",
            "Why can't I scan, or why does it say permission is missing?",
        ),
        StringKey.HELP_A_PERMISSION to StringsEntry(
            "扫描需要蓝牙权限。Android 12 及以上请授予「附近的设备」权限，Android 11 及以下请授予定位权限；iOS 需允许应用使用蓝牙。若曾拒绝，请到系统设置中为本应用开启权限，返回应用后重试。",
            "Scanning needs Bluetooth permission. On Android 12+ grant Nearby devices; on Android 11 and below grant location. On iOS allow Bluetooth access. If you denied it, enable it in system settings for this app, come back, and retry.",
        ),
        StringKey.HELP_Q_CONNECT to StringsEntry("连接失败或频繁断开怎么办？", "Connection fails or keeps dropping?"),
        StringKey.HELP_A_CONNECT to StringsEntry(
            "先确认设备已上电、处于广播模式并靠近手机，蓝牙已开启。连接超时或设备侧断开时，应用会自动按退避间隔重连（最多 5 次）；可随时取消并手动重试。蓝牙关闭期间重连会暂停，开启后继续。",
            "Confirm the device is powered on, advertising, close to the phone, and Bluetooth is on. On timeout or device-side disconnect the app retries with backoff (up to 5 attempts); you can cancel and retry manually. Retries pause while Bluetooth is off and resume afterwards.",
        ),
        StringKey.HELP_Q_OTA to StringsEntry("OTA 升级失败怎么办？", "What if an OTA update fails?"),
        StringKey.HELP_A_OTA to StringsEntry(
            "先看失败原因：CRC 不一致或固件不匹配时请重新选择正确固件并重新预检；链路断开时重新连接后可用「继续上次升级」从断点续传。断点保存在本机，成功或主动清除后删除。升级期间请保持设备靠近并避免关闭蓝牙。",
            "Read the failure reason first. On a CRC mismatch or model mismatch, select the correct firmware and re-run the precheck. If the link dropped, reconnect and use \"Resume last update\" to continue from the checkpoint. Checkpoints stay on this device and are removed after success or when cleared. Keep the device nearby and leave Bluetooth on during the update.",
        ),
        StringKey.HELP_Q_LOGS to StringsEntry("日志保存在哪里？如何导出？", "Where are logs stored and how do I export them?"),
        StringKey.HELP_A_LOGS to StringsEntry(
            "报文日志只保存在内存中（默认 500 条，滚动淘汰），不会自动落盘或上传。可在设备详情页进入「报文日志」，按命令、错误、时间范围与关键字筛选，再选择保存文件或系统分享。应用退出后内存日志会清空，需要留存请及时导出。",
            "Packet logs live in memory only (500 entries by default, oldest evicted) and are never written to disk or uploaded automatically. Open Packet logs from device details, filter by command, error, time range, and keyword, then save a file or share it. In-memory logs are cleared when the app exits, so export them before leaving.",
        ),
        StringKey.HELP_Q_DEVICES to StringsEntry("最多可以连接几台设备？", "How many devices can I connect?"),
        StringKey.HELP_A_DEVICES to StringsEntry(
            "默认最多同时连接 4 台。达到上限会直接提示，需要先断开一台；切换后详情、配置与 OTA 只作用于当前设备。",
            "Up to 4 devices can be connected at once. At the limit you must disconnect one first; switching only changes which device details, configuration, and OTA apply to.",
        ),
        StringKey.HELP_Q_QUICK to StringsEntry("快捷操作会立即生效吗？", "Do quick actions take effect immediately?"),
        StringKey.HELP_A_QUICK to StringsEntry(
            "重启与恢复出厂都需要二次确认，恢复出厂不可撤销；心跳只读取运行时长与电量。恢复出厂的确认令牌语义需固件确认。",
            "Reboot and factory reset require confirmation, and factory reset cannot be undone; heartbeat only reads uptime and battery. Confirm-token semantics must be confirmed with the firmware.",
        ),

        // ---- 隐私 ----
        StringKey.PRIVACY_TITLE to StringsEntry("隐私说明", "Privacy"),
        StringKey.PRIVACY_STORAGE_TITLE to StringsEntry("本地存储内容", "Data stored on this device"),
        StringKey.PRIVACY_ITEM_PROFILES to StringsEntry(
            "设备档案：最近连接时间、型号、固件版本、电量与 OTA 结果。",
            "Device profiles: last connection time, model, firmware, battery, and OTA outcomes.",
        ),
        StringKey.PRIVACY_ITEM_OTA to StringsEntry(
            "OTA 断点：续传偏移、会话与固件标识，用于断点续传。",
            "OTA checkpoints: resume offset, session, and firmware identity used for resuming.",
        ),
        StringKey.PRIVACY_ITEM_TOKEN to StringsEntry(
            "会话令牌：鉴权成功后保存；Android 使用 Keystore 加密，iOS 使用 Keychain。",
            "Session token: stored after authentication; encrypted with Keystore on Android and Keychain on iOS.",
        ),
        StringKey.PRIVACY_ITEM_LOGS to StringsEntry(
            "报文日志：仅内存环形缓冲，不落盘；退出应用即清空。",
            "Packet logs: memory-only ring buffer, never written to disk; cleared when the app exits.",
        ),
        StringKey.PRIVACY_ITEM_SETTINGS to StringsEntry(
            "语言与主题偏好：仅记录选择结果。",
            "Language and theme preferences: only the selected values.",
        ),
        StringKey.PRIVACY_ITEM_PERMISSION_FLAG to StringsEntry(
            "权限请求标记：记录是否已请求过 Bluetooth 权限（Android）。",
            "Permission flag: whether Bluetooth permission has been requested (Android).",
        ),
        StringKey.PRIVACY_LOCATION_LABEL to StringsEntry("存储位置：", "Location: "),
        StringKey.PRIVACY_KEYS_LABEL to StringsEntry("键名：", "Keys: "),
        StringKey.PRIVACY_NOT_PERSISTED to StringsEntry(
            "仅内存，退出即丢失",
            "Memory only; lost on exit",
        ),
        StringKey.PRIVACY_NETWORK_TITLE to StringsEntry("是否上传数据", "Uploads"),
        StringKey.PRIVACY_NETWORK_BODY to StringsEntry(
            "当前版本没有网络上传功能：应用不包含联网权限，设备档案、断点、令牌与日志都不会离开本机。",
            "This version has no network upload: the app requests no internet permission, and profiles, checkpoints, tokens, and logs never leave this device.",
        ),
        StringKey.PRIVACY_DELETE_TITLE to StringsEntry("如何清除", "How to erase"),
        StringKey.PRIVACY_DELETE_BODY to StringsEntry(
            "在系统设置中清除应用数据或卸载应用即可删除上述本地数据。报文日志退出应用后自动清空；OTA 断点可在升级页手动清除。",
            "Clear the app's data in system settings or uninstall the app to remove the local data above. Packet logs clear when the app exits; OTA checkpoints can be cleared on the update page.",
        ),
        StringKey.PRIVACY_JVM_NOTE to StringsEntry(
            "当前运行在测试宿主：所有数据仅存在于内存。",
            "Running on the test host: all data exists in memory only.",
        ),

        // ---- 多设备管理（v2.3.0） ----
        StringKey.DEVICES_TITLE to StringsEntry("已连接设备", "Connected devices"),
        StringKey.DEVICES_ENTRY to StringsEntry("已连接设备（{count}）", "Connected devices ({count})"),
        StringKey.DEVICES_EMPTY to StringsEntry(
            "当前没有已连接设备，请返回扫描页连接。",
            "No connected devices. Return to the scan page to connect.",
        ),
        StringKey.DEVICES_SLOT to StringsEntry("槽位 {slot}/{capacity}", "Slot {slot}/{capacity}"),
        StringKey.DEVICES_CONNECTED_AT to StringsEntry("连接于 {time}", "Connected at {time}"),
        StringKey.DEVICES_ACTIVE to StringsEntry("当前操作", "Active"),
        StringKey.DEVICES_SWITCH to StringsEntry("切换", "Switch"),
        StringKey.DEVICES_DISCONNECT to StringsEntry("断开", "Disconnect"),
        StringKey.DEVICES_DISCONNECT_ALL to StringsEntry("全部断开", "Disconnect all"),
        StringKey.DEVICES_LIMIT_REACHED to StringsEntry(
            "已达连接上限（{capacity} 台），请先断开一台再连接新设备。",
            "Connection limit reached ({capacity}). Disconnect one device before connecting another.",
        ),
        StringKey.DEVICES_LIMIT_POLICY to StringsEntry(
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
        StringKey.QUICK_ACTION_FAILED to StringsEntry(
            "操作失败：{detail}。请重试或重新连接设备。",
            "Action failed: {detail}. Please retry or reconnect the device.",
        ),
        StringKey.QUICK_RUNNING to StringsEntry("操作进行中…", "Action in progress…"),
        StringKey.QUICK_REBOOT_CONFIRM_TITLE to StringsEntry("确认重启", "Confirm reboot"),
        StringKey.QUICK_REBOOT_CONFIRM_MESSAGE to StringsEntry(
            "设备将重启并短暂断开连接，期间无法执行其它操作。",
            "The device will reboot and disconnect briefly; other actions are unavailable during this time.",
        ),
        StringKey.QUICK_REBOOT_DELAY_LABEL to StringsEntry("延迟（毫秒）", "Delay (ms)"),
        StringKey.QUICK_FACTORY_CONFIRM_TITLE to StringsEntry("确认恢复出厂", "Confirm factory reset"),
        StringKey.QUICK_FACTORY_CONFIRM_MESSAGE to StringsEntry(
            "设备将清除全部配置与用户数据。",
            "The device will erase all configuration and user data.",
        ),
        StringKey.QUICK_FACTORY_IRREVERSIBLE to StringsEntry(
            "此操作不可撤销，请确认已完成必要备份。",
            "This action cannot be undone. Make sure any required backup is complete.",
        ),
        StringKey.QUICK_FIRMWARE_CONFIRM to StringsEntry(
            "确认令牌语义需固件确认；当前协议只约定为字符串字段。",
            "The confirm-token semantics must be confirmed with the firmware; the protocol only defines it as a string.",
        ),
        StringKey.QUICK_CONFIRM_TOKEN_LABEL to StringsEntry("确认令牌（可选）", "Confirm token (optional)"),
        StringKey.QUICK_REBOOT_SUCCESS to StringsEntry(
            "重启指令已被设备接受（延迟 {delay} ms）。",
            "Reboot accepted by the device (delay {delay} ms).",
        ),
        StringKey.QUICK_FACTORY_SUCCESS to StringsEntry(
            "恢复出厂指令已被设备接受。",
            "Factory reset accepted by the device.",
        ),
        StringKey.QUICK_HEARTBEAT_SUCCESS to StringsEntry(
            "心跳正常：运行 {uptime}，电量 {percent}%。",
            "Heartbeat OK: uptime {uptime}, battery {percent}%.",
        ),

        // ---- OTA 历史（v2.3.0） ----
        StringKey.OTA_HISTORY_TITLE to StringsEntry("OTA 历史", "OTA history"),
        StringKey.OTA_HISTORY_EMPTY to StringsEntry(
            "尚未产生 OTA 记录。完成一次升级后会在此显示。",
            "No OTA records yet. Records appear here after an update.",
        ),
        StringKey.OTA_HISTORY_PRIVACY to StringsEntry(
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
    )

    /** 查表；缺失 key 直接抛出，避免静默显示空文案。 */
    fun get(language: ResolvedLanguage, key: StringKey): String {
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
fun resolveLanguage(mode: AppLanguageMode, systemLanguageTag: String?): ResolvedLanguage = when (mode) {
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
class StringResolver(val language: ResolvedLanguage) {

    fun t(key: StringKey): String = AppStrings.get(language, key)

    fun t(key: StringKey, vararg args: Pair<String, String>): String =
        AppStrings.format(language, key, args.toMap())
}

/** 当前语言；默认中文，由根界面按偏好覆盖后立即重组。 */
val LocalStrings = staticCompositionLocalOf { StringResolver(ResolvedLanguage.CHINESE) }
