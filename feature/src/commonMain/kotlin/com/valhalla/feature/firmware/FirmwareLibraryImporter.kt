package com.valhalla.feature.firmware

import com.valhalla.core.firmware.FirmwareException
import com.valhalla.core.firmware.FirmwareLibraryImportCandidate
import com.valhalla.core.firmware.FirmwareLibraryImportResult
import com.valhalla.core.firmware.FirmwareLibraryRepository
import com.valhalla.core.firmware.FirmwarePackage
import com.valhalla.core.firmware.FirmwareParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 一次固件库导入的用户可见结果。 */
sealed interface FirmwareImportOutcome {

    data class Imported(val recordName: String) : FirmwareImportOutcome

    data class Duplicate(val recordName: String) : FirmwareImportOutcome

    data class Rejected(val code: String, val detail: String?) : FirmwareImportOutcome

    data object Cancelled : FirmwareImportOutcome
}

/**
 * 从系统文件选择器导入一个固件包到本地库。
 *
 * 导入阶段执行结构解析与 payload CRC-32 复核：文件头损坏、截断、CRC 不符都按具体
 * [FirmwareException.code] 拒绝入库，**不会**只凭文件名/版本号入库。签名与型号/版本
 * 预检依赖具体设备，仍在使用前由 [FirmwarePrecheckFactory] 完整执行，导入不跳过这些校验。
 */
class FirmwareLibraryImporter(
    private val picker: FirmwarePicker,
    private val library: FirmwareLibraryRepository,
) {

    suspend fun importOne(): FirmwareImportOutcome {
        val picked = try {
            picker.pickFirmware()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            return FirmwareImportOutcome.Rejected(
                code = "LIBRARY_PICK_FAILED",
                detail = error.message,
            )
        } ?: return FirmwareImportOutcome.Cancelled

        val sourcePath = picked.sourcePath
            ?: return FirmwareImportOutcome.Rejected(
                code = "LIBRARY_REFERENCE_UNSUPPORTED",
                detail = picked.name,
            ).also { runCatching { picked.source.close() } }

        var parsed: FirmwarePackage? = null
        return try {
            val checksums = withContext(Dispatchers.Default) {
                val packageUnderTest = FirmwareParser.parse(picked.name, picked.source)
                parsed = packageUnderTest
                packageUnderTest to packageUnderTest.scanPayload()
            }
            val (firmware, scan) = checksums
            if (scan.crc32 != firmware.payloadCrc32) {
                FirmwareImportOutcome.Rejected(
                    code = "firmware.payload_crc",
                    detail = "declared=${firmware.payloadCrc32} actual=${scan.crc32}",
                )
            } else {
                when (
                    val result = library.import(
                        FirmwareLibraryImportCandidate(
                            fileName = picked.name,
                            version = firmware.version.toString(),
                            channel = firmware.version.channel,
                            modelWhitelist = firmware.header.targetModels,
                            totalBytes = firmware.payloadLength,
                            crc32 = scan.crc32,
                            sourcePath = sourcePath,
                        ),
                    )
                ) {
                    is FirmwareLibraryImportResult.Imported ->
                        FirmwareImportOutcome.Imported(result.record.displayName)

                    is FirmwareLibraryImportResult.Duplicate ->
                        FirmwareImportOutcome.Duplicate(result.existing.displayName)

                    is FirmwareLibraryImportResult.Rejected ->
                        FirmwareImportOutcome.Rejected(result.code, result.detail)
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: FirmwareException) {
            FirmwareImportOutcome.Rejected(error.code, error.message)
        } catch (error: Throwable) {
            FirmwareImportOutcome.Rejected("LIBRARY_IMPORT_FAILED", error.message)
        } finally {
            val packageUnderTest = parsed
            if (packageUnderTest != null) {
                packageUnderTest.close()
            } else {
                runCatching { picked.source.close() }
            }
        }
    }
}
