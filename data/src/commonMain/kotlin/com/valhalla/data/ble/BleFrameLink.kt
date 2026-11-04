package com.valhalla.data.ble

import com.valhalla.core.command.FrameLink
import com.valhalla.core.protocol.Frame
import com.valhalla.core.protocol.FrameCodec
import com.valhalla.core.protocol.FrameParser
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 把一条 BLE GATT 链路适配为协议栈的 [FrameLink]。
 *
 * 上行：订阅 [PROTOCOL_NOTIFY] 的通知/指示，本次连接收到的每个数据块交给独立的
 * [FrameParser] 增量拼接（BLE 通知存在分包与粘包），凑齐的完整帧——包括设备应答与
 * 主动通知——统一从 [incoming] 发出，由调度器按 sequence 分流。
 *
 * 下行：[FrameCodec] 编码后用 [writeMutex] 串行写入 [PROTOCOL_WRITE]，保证并发请求、
 * OTA 分片与鉴权不会在 GATT 层互相覆盖（部分平台一次只允许一个在途写操作）。
 *
 * 平台落地：
 * - Android：`AndroidBleConnection` 使用 `BluetoothGatt.writeCharacteristic` 写入，
 *   并在 `onCharacteristicChanged` 回调中把 notify / indicate 两种上报送入通知流；
 * - iOS：`IosBleConnection` 使用 `CBPeripheral.writeValue` 写入，
 *   并在 `didUpdateValueForCharacteristic` 回调中接收 notify / indicate。
 *
 * [incoming] 是冷流：每个收集方拥有独立的 [FrameParser] 与通知订阅，
 * 因此同一个链路应只交给一个调度器收集。
 */
class BleFrameLink(
    private val connection: BleConnection,
    private val serviceUuid: String = BleUuids.PROTOCOL_SERVICE,
    private val writeCharacteristicUuid: String = BleUuids.PROTOCOL_WRITE,
    private val notifyCharacteristicUuid: String = BleUuids.PROTOCOL_NOTIFY,
) : FrameLink {

    private val writeMutex = Mutex()

    override val incoming: Flow<Frame> = flow {
        val parser = FrameParser()
        connection.observe(serviceUuid, notifyCharacteristicUuid).collect { chunk ->
            parser.feed(chunk).forEach { frame -> emit(frame) }
        }
    }

    override suspend fun send(frame: Frame) {
        val encoded = FrameCodec.encode(frame)
        writeMutex.withLock {
            connection.write(
                serviceUuid = serviceUuid,
                characteristicUuid = writeCharacteristicUuid,
                payload = encoded,
                withResponse = true,
            )
        }
    }
}

/** 便捷工厂：把已建立连接的链路包装成协议传输层。 */
fun BleConnection.toFrameLink(
    serviceUuid: String = BleUuids.PROTOCOL_SERVICE,
    writeCharacteristicUuid: String = BleUuids.PROTOCOL_WRITE,
    notifyCharacteristicUuid: String = BleUuids.PROTOCOL_NOTIFY,
): FrameLink = BleFrameLink(this, serviceUuid, writeCharacteristicUuid, notifyCharacteristicUuid)
