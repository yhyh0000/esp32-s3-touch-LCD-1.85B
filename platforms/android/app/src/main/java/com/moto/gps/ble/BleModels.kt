package com.moto.gps.ble

import android.bluetooth.BluetoothDevice

enum class BleLinkState {
    IDLE,
    SCANNING,
    CONNECTING,
    DISCOVERING,
    NEGOTIATING_MTU,
    SUBSCRIBING,
    PAIRING,
    HANDSHAKING,
    READY,
    ERROR,
}

data class DiscoveredBleDevice(
    val device: BluetoothDevice,
    val name: String,
    val address: String,
    val rssi: Int,
)

data class BleUiState(
    val linkState: BleLinkState = BleLinkState.IDLE,
    val devices: List<DiscoveredBleDevice> = emptyList(),
    val selectedAddress: String? = null,
    val protocolReady: Boolean = false,
    val message: String = "未连接",
    val protocolSelfCheck: String = "未运行",
)
