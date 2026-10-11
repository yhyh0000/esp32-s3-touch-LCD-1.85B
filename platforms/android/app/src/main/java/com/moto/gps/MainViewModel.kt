package com.moto.gps

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.moto.gps.ble.BleGattClient
import com.moto.gps.ble.BleUiState
import com.moto.gps.protocol.NativeBleCodec
import kotlinx.coroutines.flow.StateFlow

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val client = BleGattClient(application.applicationContext)
    val uiState: StateFlow<BleUiState> = client.uiState

    fun scan() = client.startScan()

    fun connect(address: String) {
        client.uiState.value.devices
            .firstOrNull { it.address == address }
            ?.let { device -> client.connect(device) }
    }

    fun disconnect() = client.disconnect()

    fun runProtocolSelfCheck() {
        val result = runCatching {
            NativeBleCodec().use { codec ->
                val golden = codec.goldenVectorCheck()
                val coordinates = codec.coordinateSanityCheck()
                "黄金字节=${if (golden) "通过" else "失败"}；坐标核心=${if (coordinates) "通过" else "失败"}"
            }
        }.getOrElse { "自检异常：${it.message}" }
        client.setSelfCheckResult(result)
    }

    override fun onCleared() {
        client.close()
        super.onCleared()
    }
}
