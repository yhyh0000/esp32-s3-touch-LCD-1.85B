package com.moto.gps.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.util.Log
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.moto.gps.protocol.NativeBleCodec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.ArrayDeque
import java.util.UUID

private const val SCAN_DURATION_MS = 10_000L
private const val MAX_REQUESTED_MTU = 517
private const val DEFAULT_FRAME_SIZE = 20
private const val GATT_SUCCESS = 0
private const val GATT_INSUFFICIENT_AUTHENTICATION = 5
private const val GATT_INSUFFICIENT_ENCRYPTION = 15

/** Android GATT central for the existing 1.85B firmware. */
@SuppressLint("MissingPermission")
class BleGattClient(private val context: Context) : AutoCloseable {
    private companion object {
        const val TAG = "MotoBleGatt"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val bluetoothManager =
        context.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter?
        get() = bluetoothManager?.adapter
    private val codec = NativeBleCodec(DEFAULT_FRAME_SIZE)
    private val _uiState = MutableStateFlow(BleUiState())
    val uiState: StateFlow<BleUiState> = _uiState

    private var scanCallback: ScanCallback? = null
    private var scanStop: Runnable? = null
    private var gatt: BluetoothGatt? = null
    private var rxCharacteristic: BluetoothGattCharacteristic? = null
    private var txCharacteristic: BluetoothGattCharacteristic? = null
    private var frameSize = DEFAULT_FRAME_SIZE
    private var authRetryPending = false
    private var cacheRefreshAttempted = false
    private var closed = false

    private val heartbeatTick = object : Runnable {
        override fun run() {
            if (closed) return
            session.tick()
            mainHandler.postDelayed(this, 250L)
        }
    }

    private sealed interface GattOperation {
        fun start(gatt: BluetoothGatt): Boolean
    }

    private class DescriptorWrite(
        private val descriptor: BluetoothGattDescriptor,
        private val value: ByteArray,
    ) : GattOperation {
        override fun start(gatt: BluetoothGatt): Boolean {
            return if (Build.VERSION.SDK_INT >= 33) {
                gatt.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                run {
                    descriptor.value = value
                    gatt.writeDescriptor(descriptor)
                }
            }
        }
    }

    private class CharacteristicWrite(
        private val characteristic: BluetoothGattCharacteristic,
        private val value: ByteArray,
        private val writeType: Int,
    ) : GattOperation {
        override fun start(gatt: BluetoothGatt): Boolean {
            return if (Build.VERSION.SDK_INT >= 33) {
                gatt.writeCharacteristic(characteristic, value, writeType) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                run {
                    characteristic.writeType = writeType
                    characteristic.value = value
                    gatt.writeCharacteristic(characteristic)
                }
            }
        }
    }

    private val pendingOperations = ArrayDeque<GattOperation>()
    private var activeOperation: GattOperation? = null

    private val session = com.moto.gps.ble.BleSessionController(
        codec = codec,
        sendFrames = { frames, withResponse -> enqueueFrames(frames, withResponse) },
        onStateChanged = { state, message ->
            val uiState = if (state == ProtocolSessionState.READY) {
                BleLinkState.READY
            } else if (state == ProtocolSessionState.FAILED) {
                BleLinkState.ERROR
            } else {
                BleLinkState.HANDSHAKING
            }
            updateState(linkState = uiState, protocolReady = state == ProtocolSessionState.READY, message = message)
        },
    )

    private val bondReceiver = object : BroadcastReceiver() {
        override fun onReceive(receiverContext: Context, intent: Intent) {
            if (intent.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
            val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
            if (device?.address != gatt?.device?.address) return
            val bondState = intent.getIntExtra(
                BluetoothDevice.EXTRA_BOND_STATE,
                BluetoothDevice.BOND_NONE,
            )
            if (bondState == BluetoothDevice.BOND_BONDED && authRetryPending) {
                authRetryPending = false
                mainHandler.post {
                    updateState(linkState = BleLinkState.DISCOVERING, message = "配对完成，重新发现服务")
                    gatt?.discoverServices()
                }
            } else if (bondState == BluetoothDevice.BOND_NONE && authRetryPending) {
                authRetryPending = false
                fail("蓝牙配对失败，未关闭固件加密权限")
            }
        }
    }

    init {
        val filter = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        ContextCompat.registerReceiver(
            context,
            bondReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        mainHandler.post(heartbeatTick)
    }

    fun startScan() {
        if (!hasScanPermission()) {
            fail("缺少 BLE 扫描权限")
            return
        }
        val scanner = try {
            adapter?.bluetoothLeScanner
        } catch (error: SecurityException) {
            fail("缺少系统蓝牙权限：${error.message ?: "BLUETOOTH"}")
            return
        }
        if (scanner == null) {
            fail("蓝牙不可用或已关闭")
            return
        }
        stopScan()
        updateState(linkState = BleLinkState.SCANNING, devices = emptyList(), message = "正在扫描 MOTO GPS")
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        val filters = listOf(
            ScanFilter.Builder()
                .setServiceUuid(android.os.ParcelUuid(MotoBleUuids.service))
                .build(),
        )
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device
                val item = DiscoveredBleDevice(
                    device = device,
                    name = result.scanRecord?.deviceName ?: "MOTO GPS",
                    address = device.address,
                    rssi = result.rssi,
                )
                val current = _uiState.value.devices.filterNot { it.address == item.address }
                updateState(devices = (current + item).sortedByDescending { it.rssi })
            }

            override fun onScanFailed(errorCode: Int) {
                fail("BLE 扫描失败：$errorCode")
            }
        }
        scanCallback = callback
        try {
            scanner.startScan(filters, settings, callback)
        } catch (error: SecurityException) {
            scanCallback = null
            fail("缺少系统蓝牙扫描权限：${error.message ?: "BLUETOOTH_SCAN"}")
            return
        }
        scanStop = Runnable { stopScan() }
        mainHandler.postDelayed(scanStop!!, SCAN_DURATION_MS)
    }

    fun connect(device: BluetoothDevice) {
        connectInternal(device, resetCacheRefresh = true)
    }

    private fun connectInternal(device: BluetoothDevice, resetCacheRefresh: Boolean) {
        if (!hasConnectPermission()) {
            fail("缺少 BLE 连接权限")
            return
        }
        if (resetCacheRefresh) {
            cacheRefreshAttempted = false
        }
        stopScan()
        closeGattOnly()
        updateState(
            linkState = BleLinkState.CONNECTING,
            selectedAddress = device.address,
            protocolReady = false,
            message = "正在连接 ${device.name ?: device.address}",
        )
        gatt = if (Build.VERSION.SDK_INT >= 23) {
            device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        } else {
            @Suppress("DEPRECATION")
            device.connectGatt(context, false, callback)
        }
    }

    fun disconnect() {
        mainHandler.post {
            session.stop()
            gatt?.disconnect()
            closeGattOnly()
            updateState(linkState = BleLinkState.IDLE, protocolReady = false, message = "已断开")
        }
    }

    fun tick() {
        session.tick()
    }

    fun setSelfCheckResult(result: String) {
        updateState(protocolSelfCheck = result)
    }

    override fun close() {
        if (closed) return
        closed = true
        stopScan()
        context.unregisterReceiver(bondReceiver)
        mainHandler.removeCallbacks(heartbeatTick)
        session.stop()
        closeGattOnly()
        codec.close()
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            mainHandler.post {
                Log.i(TAG, "connection state address=${gatt.device.address} status=$status state=$newState")
                if (status != GATT_SUCCESS || newState != BluetoothProfile.STATE_CONNECTED) {
                    fail("GATT 连接断开：status=$status state=$newState")
                    session.stop()
                    closeGattOnly()
                    return@post
                }
                updateState(linkState = BleLinkState.DISCOVERING, message = "已连接，正在发现服务")
                gatt.discoverServices()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            mainHandler.post {
                val serviceSummary = gatt.services.orEmpty().joinToString(";") { service ->
                    val characteristics = service.characteristics.joinToString(",") { it.uuid.toString() }
                    "${service.uuid}[$characteristics]"
                }
                Log.i(TAG, "services discovered status=$status services=$serviceSummary")
                if (status != GATT_SUCCESS) {
                    fail("服务发现失败：$status")
                    return@post
                }
                val service = gatt.getService(MotoBleUuids.service)
                rxCharacteristic = service?.getCharacteristic(MotoBleUuids.phoneToDevice)
                txCharacteristic = service?.getCharacteristic(MotoBleUuids.deviceToPhone)
                if (service == null || rxCharacteristic == null || txCharacteristic == null) {
                    if (!cacheRefreshAttempted && refreshGattCache(gatt)) {
                        cacheRefreshAttempted = true
                        val device = gatt.device
                        updateState(
                            linkState = BleLinkState.DISCOVERING,
                            protocolReady = false,
                            message = "发现旧 GATT 服务，已刷新缓存并重连",
                        )
                        gatt.disconnect()
                        gatt.close()
                        if (this@BleGattClient.gatt === gatt) {
                            this@BleGattClient.gatt = null
                        }
                        mainHandler.postDelayed(
                            { connectInternal(device, resetCacheRefresh = false) },
                            700L,
                        )
                        return@post
                    }
                    fail("未找到 1.85B MOTO GPS 导航服务或特征；实际服务=${serviceSummary.ifBlank { "<空>" }}")
                    return@post
                }
                requestMtu(gatt)
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            mainHandler.post {
                frameSize = if (status == GATT_SUCCESS) {
                    (mtu - 3).coerceIn(DEFAULT_FRAME_SIZE, 512)
                } else {
                    DEFAULT_FRAME_SIZE
                }
                codec.setMaximumFrameSize(frameSize)
                subscribeNotifications(gatt)
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            mainHandler.post { completeOperation(status, descriptor) }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            mainHandler.post { completeOperation(status, characteristic) }
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            onNotification(characteristic.value ?: byteArrayOf())
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            onNotification(value)
        }
    }

    private fun requestMtu(gatt: BluetoothGatt) {
        updateState(linkState = BleLinkState.NEGOTIATING_MTU, message = "协商 BLE MTU")
        if (!gatt.requestMtu(MAX_REQUESTED_MTU)) {
            frameSize = DEFAULT_FRAME_SIZE
            codec.setMaximumFrameSize(frameSize)
            subscribeNotifications(gatt)
        }
    }

    private fun refreshGattCache(gatt: BluetoothGatt): Boolean {
        return try {
            val refresh = gatt.javaClass.getMethod("refresh")
            val result = refresh.invoke(gatt) as? Boolean ?: false
            Log.i(TAG, "refresh GATT cache result=$result")
            result
        } catch (error: ReflectiveOperationException) {
            Log.w(TAG, "refresh GATT cache unavailable", error)
            false
        } catch (error: SecurityException) {
            Log.w(TAG, "refresh GATT cache denied", error)
            false
        }
    }

    private fun subscribeNotifications(gatt: BluetoothGatt) {
        val tx = txCharacteristic ?: return fail("TX 特征丢失")
        val descriptor = tx.getDescriptor(MotoBleUuids.cccd)
            ?: return fail("TX 没有 CCCD，无法订阅通知")
        if (!gatt.setCharacteristicNotification(tx, true)) {
            fail("本地启用 TX 通知失败")
            return
        }
        updateState(linkState = BleLinkState.SUBSCRIBING, message = "订阅 TX Notify，必要时触发配对")
        enqueue(DescriptorWrite(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE))
    }

    private fun onNotification(value: ByteArray) {
        mainHandler.post {
            val event = codec.pushDeviceFrame(value, android.os.SystemClock.elapsedRealtime())
            session.onDeviceEvent(event)
            if (event.ackRequested && event.sequence != 0) {
                enqueueFrames(
                    codec.encodeAck(
                        sequence = event.sequence,
                        status = 0,
                        commandId = event.ackCommandId,
                    ),
                    true,
                )
            }
            if (event is com.moto.gps.protocol.NativeBleEvent.Error) {
                updateState(linkState = BleLinkState.ERROR, protocolReady = false, message = event.message)
            }
        }
    }

    private fun enqueueFrames(frames: List<ByteArray>, withResponse: Boolean) {
        mainHandler.post {
            val characteristic = rxCharacteristic ?: return@post fail("RX 特征不可用")
            val type = if (withResponse) {
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            } else {
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            }
            frames.forEach { pendingOperations.add(CharacteristicWrite(characteristic, it, type)) }
            pumpQueue()
        }
    }

    private fun enqueue(operation: GattOperation) {
        pendingOperations.add(operation)
        pumpQueue()
    }

    private fun pumpQueue() {
        if (activeOperation != null) return
        if (pendingOperations.isEmpty()) return
        val operation = pendingOperations.removeFirst()
        val currentGatt = gatt ?: return fail("GATT 已断开，清理发送队列")
        activeOperation = operation
        if (!operation.start(currentGatt)) {
            activeOperation = null
            handleGattFailure(GATT_INSUFFICIENT_AUTHENTICATION)
        }
    }

    private fun completeOperation(status: Int, callbackObject: Any) {
        val operation = activeOperation ?: return
        activeOperation = null
        if (status == GATT_SUCCESS) {
            pumpQueue()
            if (callbackObject is BluetoothGattDescriptor && callbackObject.uuid == MotoBleUuids.cccd) {
                updateState(linkState = BleLinkState.HANDSHAKING, message = "TX 已订阅，等待设备 Ready")
                session.start(frameSize)
            }
        } else {
            handleGattFailure(status)
        }
    }

    private fun handleGattFailure(status: Int) {
        if (status == GATT_INSUFFICIENT_AUTHENTICATION || status == GATT_INSUFFICIENT_ENCRYPTION) {
            pendingOperations.clear()
            authRetryPending = true
            updateState(linkState = BleLinkState.PAIRING, message = "设备要求加密连接，请完成系统蓝牙配对")
            val device = gatt?.device
            if (device != null && device.bondState != BluetoothDevice.BOND_BONDED) {
                device.createBond()
            } else {
                gatt?.discoverServices()
            }
        } else {
            fail("GATT 操作失败：$status")
        }
    }

    private fun stopScan() {
        val callback = scanCallback
        if (callback != null && hasScanPermission()) {
            try {
                adapter?.bluetoothLeScanner?.stopScan(callback)
            } catch (_: SecurityException) {
                // The scan is already being torn down; do not crash while cleaning up.
            }
        }
        scanCallback = null
        scanStop?.let(mainHandler::removeCallbacks)
        scanStop = null
        if (_uiState.value.linkState == BleLinkState.SCANNING) {
            updateState(linkState = BleLinkState.IDLE, message = "扫描结束")
        }
    }

    private fun closeGattOnly() {
        pendingOperations.clear()
        activeOperation = null
        gatt?.close()
        gatt = null
        rxCharacteristic = null
        txCharacteristic = null
        authRetryPending = false
    }

    private fun hasScanPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= 31) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            val bluetoothGranted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH,
            ) == PackageManager.PERMISSION_GRANTED
            val locationGranted = Build.VERSION.SDK_INT < 23 ||
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                ) == PackageManager.PERMISSION_GRANTED
            bluetoothGranted && locationGranted
        }
    }

    private fun hasConnectPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= 31) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH) ==
                PackageManager.PERMISSION_GRANTED
        }
    }

    private fun updateState(
        linkState: BleLinkState = _uiState.value.linkState,
        devices: List<DiscoveredBleDevice> = _uiState.value.devices,
        selectedAddress: String? = _uiState.value.selectedAddress,
        protocolReady: Boolean = _uiState.value.protocolReady,
        message: String = _uiState.value.message,
        protocolSelfCheck: String = _uiState.value.protocolSelfCheck,
    ) {
        _uiState.value = _uiState.value.copy(
            linkState = linkState,
            devices = devices,
            selectedAddress = selectedAddress,
            protocolReady = protocolReady,
            message = message,
            protocolSelfCheck = protocolSelfCheck,
        )
    }

    private fun fail(message: String) {
        updateState(linkState = BleLinkState.ERROR, protocolReady = false, message = message)
    }
}
