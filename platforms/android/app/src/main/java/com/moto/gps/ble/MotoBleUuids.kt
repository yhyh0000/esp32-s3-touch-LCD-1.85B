package com.moto.gps.ble

import java.util.UUID

object MotoBleUuids {
    val service: UUID = UUID.fromString("7e57a000-b50c-4b6a-9c57-40a54e8e1000")
    val phoneToDevice: UUID = UUID.fromString("7e57a001-b50c-4b6a-9c57-40a54e8e1000")
    val deviceToPhone: UUID = UUID.fromString("7e57a002-b50c-4b6a-9c57-40a54e8e1000")
    val cccd: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
}
