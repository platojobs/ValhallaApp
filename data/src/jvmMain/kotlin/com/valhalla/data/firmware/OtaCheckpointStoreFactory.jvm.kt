package com.valhalla.data.firmware

import com.valhalla.core.firmware.InMemoryOtaCheckpointStore
import com.valhalla.core.firmware.OtaCheckpointStore

actual fun createOtaCheckpointStore(): OtaCheckpointStore = InMemoryOtaCheckpointStore()
