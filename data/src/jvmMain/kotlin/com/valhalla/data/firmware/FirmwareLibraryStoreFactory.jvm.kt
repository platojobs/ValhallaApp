package com.valhalla.data.firmware

import com.valhalla.core.firmware.FirmwareLibraryStore
import com.valhalla.core.firmware.InMemoryFirmwareLibraryStore

actual fun createFirmwareLibraryStore(): FirmwareLibraryStore = InMemoryFirmwareLibraryStore()
