package com.moto.gps

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.moto.gps.ble.BleGattClient
import com.moto.gps.ble.BleDiagnostics
import com.moto.gps.ble.BleUiState
import com.moto.gps.navigation.NavigationController
import com.moto.gps.navigation.NavigationUiState
import com.moto.gps.navigation.PlaceResult
import com.moto.gps.navigation.RouteBundle
import com.moto.gps.protocol.NativeBleCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val client = BleGattClient(application.applicationContext)
    private val navigationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val navigationController = NavigationController(
        application.applicationContext,
        client,
        navigationScope,
    )
    val uiState: StateFlow<BleUiState> = client.uiState
    val diagnostics: StateFlow<List<String>> = BleDiagnostics.entries
    val navigationState: StateFlow<NavigationUiState> = navigationController.state

    fun scan() = client.startScan()

    fun connect(address: String) {
        client.uiState.value.devices
            .firstOrNull { it.address == address }
            ?.let { item -> client.connect(item.device) }
    }

    fun disconnect() = client.disconnect()

    fun clearDiagnostics() = BleDiagnostics.clear()

    fun diagnosticsText(): String = BleDiagnostics.copyText()

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

    fun setGatewayUrl(value: String) = navigationController.setGatewayUrl(value)

    fun setDestinationQuery(value: String) = navigationController.setQuery(value)

    fun startLocation() = navigationController.startLocation()

    fun searchPlaces() = navigationController.searchPlaces()

    fun planRoute(place: PlaceResult) = navigationController.planRoute(place)

    fun selectRoute(route: RouteBundle) = navigationController.selectRoute(route)

    fun startNavigation() = navigationController.startNavigation()

    fun stopNavigation() = navigationController.stopNavigation()

    override fun onCleared() {
        navigationController.close()
        navigationScope.cancel()
        client.close()
        super.onCleared()
    }
}
