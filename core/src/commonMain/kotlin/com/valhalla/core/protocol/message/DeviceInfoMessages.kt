package com.valhalla.core.protocol.message

import com.valhalla.core.protocol.CommandType

/** 固件版本。channel 为发布通道，例如 `stable` / `beta`。 */
data class FirmwareVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val build: Int,
    val channel: String,
) {
    init {
        require(major in 0..0xFF) { "major 超出范围: $major" }
        require(minor in 0..0xFF) { "minor 超出范围: $minor" }
        require(patch in 0..0xFF) { "patch 超出范围: $patch" }
        require(build in 0..0xFFFF) { "build 超出范围: $build" }
    }

    fun encodeTo(writer: PayloadWriter) {
        writer.byte(major)
        writer.byte(minor)
        writer.byte(patch)
        writer.u16(build)
        writer.string1(channel)
    }

    /**
     * 仅比较 `major.minor.patch.build`，忽略渠道名。
     *
     * 预检的版本回退判定、断点固件身份判定都使用这一口径：渠道切换
     * （release → beta）不属于版本回退，内容一致性由 CRC16/CRC32 兜底。
     */
    fun sameReleaseAs(other: FirmwareVersion): Boolean =
        major == other.major && minor == other.minor && patch == other.patch && build == other.build

    override fun toString(): String = "$major.$minor.$patch (build $build, $channel)"

    companion object {
        fun decode(reader: PayloadReader): FirmwareVersion = FirmwareVersion(
            major = reader.byte(),
            minor = reader.byte(),
            patch = reader.byte(),
            build = reader.u16(),
            channel = reader.string1(),
        )
    }
}

/** 读取设备信息，payload 为空。 */
class GetDeviceInfoRequest : DeviceRequest() {

    override val command: CommandType = CommandType.GET_DEVICE_INFO

    override fun encodeBody(writer: PayloadWriter) = Unit
}

/**
 * 设备信息应答。
 *
 * payload：`vendor:str1 | model:str1 | serial:str1 | hardwareVersion:str1 | firmwareVersion`
 */
class DeviceInfoResponse(
    status: DeviceStatus,
    val vendor: String,
    val model: String,
    val serialNumber: String,
    val hardwareVersion: String,
    val firmwareVersion: FirmwareVersion,
) : DeviceResponse() {

    override val command: CommandType = CommandType.GET_DEVICE_INFO

    override val status: DeviceStatus = status

    override fun encodeBody(writer: PayloadWriter) {
        writer.string1(vendor)
        writer.string1(model)
        writer.string1(serialNumber)
        writer.string1(hardwareVersion)
        firmwareVersion.encodeTo(writer)
    }
}

/** 读取电量，payload 为空。 */
class GetBatteryRequest : DeviceRequest() {

    override val command: CommandType = CommandType.GET_BATTERY

    override fun encodeBody(writer: PayloadWriter) = Unit
}

/**
 * 电量应答。
 *
 * payload：`percent:u8 | charging:u8(0/1) | voltageMv:u16`
 */
class BatteryResponse(
    status: DeviceStatus,
    val percent: Int,
    val charging: Boolean,
    val voltageMillivolts: Int,
) : DeviceResponse() {

    init {
        require(percent in 0..100) { "电量百分比超出范围: $percent" }
        require(voltageMillivolts in 0..0xFFFF) { "电压毫伏值超出范围: $voltageMillivolts" }
    }

    override val command: CommandType = CommandType.GET_BATTERY

    override val status: DeviceStatus = status

    override fun encodeBody(writer: PayloadWriter) {
        writer.byte(percent)
        writer.byte(if (charging) 1 else 0)
        writer.u16(voltageMillivolts)
    }
}

/** 读取固件版本，payload 为空。 */
class GetFirmwareVersionRequest : DeviceRequest() {

    override val command: CommandType = CommandType.GET_FIRMWARE_VERSION

    override fun encodeBody(writer: PayloadWriter) = Unit
}

/** 固件版本应答（状态码之后直接是 [FirmwareVersion]）。 */
class FirmwareVersionResponse(
    status: DeviceStatus,
    val version: FirmwareVersion,
) : DeviceResponse() {

    override val command: CommandType = CommandType.GET_FIRMWARE_VERSION

    override val status: DeviceStatus = status

    override fun encodeBody(writer: PayloadWriter) {
        version.encodeTo(writer)
    }
}

/**
 * 设置设备时间。
 *
 * payload：`epochSeconds:u32 | timezoneOffsetMinutes:i16`
 */
class SetTimeRequest(
    val epochSeconds: Long,
    val timezoneOffsetMinutes: Int,
) : DeviceRequest() {

    init {
        require(epochSeconds in 0..0xFFFFFFFFL) { "epochSeconds 超出范围: $epochSeconds" }
        require(timezoneOffsetMinutes in -720..840) { "时区偏移超出范围: $timezoneOffsetMinutes" }
    }

    override val command: CommandType = CommandType.SET_TIME

    override fun encodeBody(writer: PayloadWriter) {
        writer.u32(epochSeconds)
        writer.i16(timezoneOffsetMinutes)
    }
}

/** 设置时间应答，body 为设备实际应用的时间戳。 */
class SetTimeResponse(
    status: DeviceStatus,
    val appliedEpochSeconds: Long,
) : DeviceResponse() {

    override val command: CommandType = CommandType.SET_TIME

    override val status: DeviceStatus = status

    override fun encodeBody(writer: PayloadWriter) {
        writer.u32(appliedEpochSeconds)
    }
}

/** 读取设备时间，payload 为空。 */
class GetTimeRequest : DeviceRequest() {

    override val command: CommandType = CommandType.GET_TIME

    override fun encodeBody(writer: PayloadWriter) = Unit
}

/** 设备时间应答：`epochSeconds:u32 | timezoneOffsetMinutes:i16`。 */
class TimeResponse(
    status: DeviceStatus,
    val epochSeconds: Long,
    val timezoneOffsetMinutes: Int,
) : DeviceResponse() {

    override val command: CommandType = CommandType.GET_TIME

    override val status: DeviceStatus = status

    override fun encodeBody(writer: PayloadWriter) {
        writer.u32(epochSeconds)
        writer.i16(timezoneOffsetMinutes)
    }
}
