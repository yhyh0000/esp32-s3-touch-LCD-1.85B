package com.moto.gps

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.moto.gps.ble.BleLinkState
import com.moto.gps.navigation.NavigationUiState

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                MotoGpsScreen()
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun MotoGpsScreen(viewModel: MainViewModel = viewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val diagnostics by viewModel.diagnostics.collectAsStateWithLifecycle()
    val navigation by viewModel.navigationState.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    var permissionMessage by remember { mutableStateOf("") }
    var showDiagnostics by remember { mutableStateOf(true) }
    var gatewayUrl by remember { mutableStateOf(navigation.gatewayUrl) }
    var destinationQuery by remember { mutableStateOf(navigation.query) }
    val permissions = remember { requiredPermissions() }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        val missing = result.filterValues { !it }.keys
        permissionMessage = if (missing.isEmpty()) {
            "权限已授予"
        } else {
            "仍缺少权限：${missing.joinToString()}"
        }
    }

    LaunchedEffect(Unit) {
        val missing = permissions.filterNot {
            ContextCompat.checkSelfPermission(
                context,
                it,
            ) == PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("MOTO GPS Android", style = MaterialTheme.typography.headlineSmall)
                Text("Waveshare ESP32-S3-Touch-LCD-1.85B · vivo X200 Pro mini / Android 16")
                Text("链路：${uiState.linkState}；${uiState.message}")
                if (permissionMessage.isNotBlank()) Text(permissionMessage)
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { viewModel.runProtocolSelfCheck() }) {
                    Text("协议自检")
                }
                OutlinedButton(onClick = { permissionLauncher.launch(permissions.toTypedArray()) }) {
                    Text("检查权限")
                }
                OutlinedButton(onClick = { viewModel.scan() }) {
                    Text("扫描圆屏")
                }
            }
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("共享核心自检：${uiState.protocolSelfCheck}")
                    Spacer(Modifier.height(4.dp))
                    Text("当前阶段只验证 BLE 与协议握手，尚未发送路线或模拟位置。")
                }
            }
        }

        item {
            NavigationPanel(
                state = navigation,
                gatewayUrl = gatewayUrl,
                destinationQuery = destinationQuery,
                onGatewayUrlChanged = {
                    gatewayUrl = it
                    viewModel.setGatewayUrl(it)
                },
                onDestinationQueryChanged = {
                    destinationQuery = it
                    viewModel.setDestinationQuery(it)
                },
                onStartLocation = viewModel::startLocation,
                onSearch = viewModel::searchPlaces,
                onPlan = viewModel::planRoute,
                onSelectRoute = viewModel::selectRoute,
                onStartNavigation = viewModel::startNavigation,
                onStopNavigation = viewModel::stopNavigation,
            )
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("BLE 诊断日志（${diagnostics.size}/500）")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { showDiagnostics = !showDiagnostics }) {
                                Text(if (showDiagnostics) "收起" else "展开")
                            }
                            OutlinedButton(onClick = viewModel::clearDiagnostics) {
                                Text("清空")
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                val clipboard = context.getSystemService(
                                    android.content.ClipboardManager::class.java,
                                )
                                clipboard?.setPrimaryClip(
                                    android.content.ClipData.newPlainText(
                                        "MOTO GPS BLE diagnostics",
                                        viewModel.diagnosticsText(),
                                    ),
                                )
                            },
                        ) {
                            Text("复制日志")
                        }
                        Text(
                            "先清空，再连接一次，断开后复制全部日志",
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                    if (showDiagnostics) {
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth().height(260.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            items(diagnostics) { line ->
                                Text(line, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }

        if (uiState.devices.isEmpty()) {
            item {
                Text("尚未发现设备。请确认 1.85B 固件已启动并显示 MOTO GPS。")
            }
        } else {
            item {
                Text("选择设备（必须明确选择，不自动连接）：")
            }
            items(uiState.devices, key = { it.address }) { device ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column {
                            Text(device.name)
                            Text("${device.address} · RSSI ${device.rssi}")
                        }
                        Button(onClick = { viewModel.connect(device.address) }) {
                            Text("连接")
                        }
                    }
                }
            }
        }

        if (uiState.linkState == BleLinkState.READY) {
            item {
                OutlinedButton(onClick = viewModel::disconnect) {
                    Text("断开圆屏")
                }
            }
        }
    }
}

private fun requiredPermissions(): List<String> = buildList {
    if (Build.VERSION.SDK_INT >= 31) {
        add(Manifest.permission.BLUETOOTH_SCAN)
        add(Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
    }
    if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    add(Manifest.permission.ACCESS_FINE_LOCATION)
    add(Manifest.permission.ACCESS_COARSE_LOCATION)
}

@androidx.compose.runtime.Composable
private fun NavigationPanel(
    state: NavigationUiState,
    gatewayUrl: String,
    destinationQuery: String,
    onGatewayUrlChanged: (String) -> Unit,
    onDestinationQueryChanged: (String) -> Unit,
    onStartLocation: () -> Unit,
    onSearch: () -> Unit,
    onPlan: (com.moto.gps.navigation.PlaceResult) -> Unit,
    onSelectRoute: (com.moto.gps.navigation.RouteBundle) -> Unit,
    onStartNavigation: () -> Unit,
    onStopNavigation: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("真实导航（第一版）", style = MaterialTheme.typography.titleMedium)
            Text("网关只接收 WGS84；路线几何按接口返回的 GCJ-02 原样发送到圆屏。")
            OutlinedTextField(
                value = gatewayUrl,
                onValueChange = onGatewayUrlChanged,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("网关地址，例如 https://your-domain/moto-gps/api") },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onStartLocation) { Text("启动定位") }
                Text(
                    state.fix?.let { "定位 ${it.latitudeDeg.format6()}, ${it.longitudeDeg.format6()} · ±${it.accuracyM.toInt()}m" }
                        ?: "尚无有效定位",
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
            OutlinedTextField(
                value = destinationQuery,
                onValueChange = onDestinationQueryChanged,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("搜索目的地") },
            )
            Button(onClick = onSearch, enabled = gatewayUrl.isNotBlank() && destinationQuery.isNotBlank()) {
                Text("搜索目的地")
            }
            state.places.forEach { place ->
                OutlinedButton(onClick = { onPlan(place) }, modifier = Modifier.fillMaxWidth()) {
                    Text("${place.name}${place.address.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""}")
                }
            }
            state.routes.forEach { route ->
                val selected = route.routeId == state.selectedRouteId
                OutlinedButton(
                    onClick = { onSelectRoute(route) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("${if (selected) "✓ " else ""}${route.totalDistanceM}m / ${route.totalDurationS}s · ${route.routeId}")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onStartNavigation, enabled = state.selectedRouteId != null && !state.active) {
                    Text("开始导航")
                }
                OutlinedButton(onClick = onStopNavigation, enabled = state.active) {
                    Text("结束导航")
                }
            }
            Text("导航状态：${state.message}")
        }
    }
}

private fun Double.format6(): String = "%.6f".format(this)
