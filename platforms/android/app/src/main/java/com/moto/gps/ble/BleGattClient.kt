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
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
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
private const val GATT_START_REJECTED = -1
private const val GATT_OPERATION_TIMEOUT_MS = 4_000L
private const val MAX_PENDING_OPERATIONS = 64

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

    private data class GattStartResult(
        val accepted: Boolean,
        val statusCode: Int,
    )

    private sealed interface GattOperation {
        val description: String
        /** Whether Android will deliver a completion callback for this operation. */
        val awaitsCallback: Boolean
        fun start(gatt: BluetoothGatt): GattStartResult
    }

    private class DescriptorWrite(
        private val descriptor: BluetoothGattDescriptor,
        private val value: ByteArray,
    ) : GattOperation {
        override val description: String
            get() = "CCCD ${descriptor.uuid} bytes=${value.size}"

        override val awaitsCallback: Boolean = true

        override fun start(gatt: BluetoothGatt): GattStartResult {
            return if (Build.VERSION.SDK_INT >= 33) {
                val status = gatt.writeDescriptor(descriptor, value)
                GattStartResult(status == BluetoothStatusCodes.SUCCESS, status)
            } else {
                @Suppress("DEPRECATION")
                run {
                    descriptor.value = value
                    val accepted = gatt.writeDescriptor(descriptor)
                    GattStartResult(
                        accepted = accepted,
                        statusCode = if (accepted) BluetoothStatusCodes.SUCCESS else GATT_START_REJECTED,
                    )
                }
            }
        }
    }

    private class CharacteristicWrite(
        private val characteristic: BluetoothGattCharacteristic,
        private val value: ByteArray,
        private val writeType: Int,
    ) : GattOperation {
        override val description: String
            get() = "RX write type=${writeTypeName(writeType)} bytes=${value.size}"

        // WRITE_TYPE_NO_RESPONSE is completed by the immediate return value of
        // writeCharacteristic(). Waiting for onCharacteristicWrite() here
        // would stall the serialized queue on Android 16; the callback is only
        // required for WRITE_TYPE_DEFAULT.
        override val awaitsCallback: Boolean =
            writeType == BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT

        override fun start(gatt: BluetoothGatt): GattStartResult {
            return if (Build.VERSION.SDK_INT >= 33) {
                val status = gatt.writeCharacteristic(characteristic, value, writeType)
                GattStartResult(status == BluetoothStatusCodes.SUCCESS, status)
            } else {
                @Suppress("DEPRECATION")
                run {
                    characteristic.writeType = writeType
                    characteristic.value = value
                    val accepted = gatt.writeCharacteristic(characteristic)
                    GattStartResult(
                        accepted = accepted,
                        statusCode = if (accepted) BluetoothStatusCodes.SUCCESS else GATT_START_REJECTED,
                    )
                }
            }
        }
    }

    private val pendingOperations = ArrayDeque<GattOperation>()
    private var activeOperation: GattOperation? = null
    private var activeOperationId = 0L
    private var activeOperationStartedAtMs = 0L
    private var operationTimeoutRunnable: Runnable? = null

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
            BleDiagnostics.i(
                "MotoBleSession",
                "state changed state=$state mappedLinkState=$uiState " +
                    "protocolReady=${state == ProtocolSessionState.READY} message=$message",
            )
            updateState(linkState = uiState, protocolReady = state == ProtocolSessionState.READY, message = message)
            if (state == ProtocolSessionState.FAILED) {
                abortGatt("协议会话失败：$message")
            }
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
        BleDiagnostics.i(TAG, "scan requested")
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
        BleDiagnostics.i(TAG, "scan started durationMs=$SCAN_DURATION_MS service=${MotoBleUuids.service}")
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
                if (current.size == _uiState.value.devices.size) {
                    BleDiagnostics.i(TAG, "scan result name=${item.name} address=${item.address} rssi=${item.rssi}")
                }
            }

            override fun onScanFailed(errorCode: Int) {
                BleDiagnostics.e(TAG, "scan failed errorCode=$errorCode")
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
        Log.i(TAG, "connect start address=${device.address} bondState=${device.bondState}")
        BleDiagnostics.i(TAG, "connect start address=${device.address} bondState=${device.bondState}")
        gatt = if (Build.VERSION.SDK_INT >= 23) {
            device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        } else {
            @Suppress("DEPRECATION")
            device.connectGatt(context, false, callback)
        }
    }

    fun disconnect() {
        mainHandler.post {
            BleDiagnostics.i(TAG, "user disconnect requested address=${gatt?.device?.address}")
            session.stop()
            val currentGatt = gatt
            currentGatt?.disconnect()
            closeGattOnly(currentGatt)
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
        BleDiagnostics.i(TAG, "client close")
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
                if (!isCurrentGatt(gatt)) return@post
                Log.i(
                    TAG,
                    "connection state address=${gatt.device.address} status=$status " +
                        "state=$newState pending=${pendingOperations.size} active=${activeOperation?.description}",
                )
                BleDiagnostics.i(
                    TAG,
                    "connection state address=${gatt.device.address} status=$status state=$newState " +
                        "pending=${pendingOperations.size} active=${activeOperation?.description}",
                )
                if (status != GATT_SUCCESS || newState != BluetoothProfile.STATE_CONNECTED) {
                    abortGatt("GATT 连接断开：status=$status state=$newState")
                    return@post
                }
                updateState(linkState = BleLinkState.DISCOVERING, message = "已连接，正在发现服务")
                val accepted = gatt.discoverServices()
                BleDiagnostics.i(TAG, "discoverServices accepted=$accepted")
                Log.i(TAG, "discoverServices accepted=$accepted")
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            mainHandler.post {
                if (!isCurrentGatt(gatt)) return@post
                val serviceSummary = gatt.services.orEmpty().joinToString(";") { service ->
                    val characteristics = service.characteristics.joinToString(",") { it.uuid.toString() }
                    "${service.uuid}[$characteristics]"
                }
                Log.i(TAG, "services discovered status=$status services=$serviceSummary")
                BleDiagnostics.i(TAG, "services discovered status=$status services=$serviceSummary")
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
                Log.i(
                    TAG,
                    "characteristics rx=${rxCharacteristic?.uuid} props=${rxCharacteristic?.properties} " +
                        "tx=${txCharacteristic?.uuid} props=${txCharacteristic?.properties}",
                )
                requestMtu(gatt)
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            mainHandler.post {
                if (!isCurrentGatt(gatt)) return@post
                frameSize = if (status == GATT_SUCCESS) {
                    (mtu - 3).coerceIn(DEFAULT_FRAME_SIZE, 512)
                } else {
                    DEFAULT_FRAME_SIZE
                }
                codec.setMaximumFrameSize(frameSize)
                Log.i(TAG, "mtu changed mtu=$mtu status=$status frameSize=$frameSize")
                BleDiagnostics.i(TAG, "mtu changed mtu=$mtu status=$status frameSize=$frameSize")
                subscribeNotifications(gatt)
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            mainHandler.post {
                if (!isCurrentGatt(gatt)) return@post
                Log.i(TAG, "descriptor write callback uuid=${descriptor.uuid} status=$status")
                BleDiagnostics.i(TAG, "descriptor write callback uuid=${descriptor.uuid} status=$status")
                completeOperation(status, descriptor)
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            mainHandler.post {
                if (!isCurrentGatt(gatt)) return@post
                Log.i(TAG, "characteristic write callback uuid=${characteristic.uuid} status=$status")
                BleDiagnostics.i(TAG, "characteristic write callback uuid=${characteristic.uuid} status=$status")
                completeOperation(status, characteristic)
            }
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            onNotification(gatt, characteristic.value ?: byteArrayOf())
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            onNotification(gatt, value)
        }
    }

    private fun requestMtu(gatt: BluetoothGatt) {
        updateState(linkState = BleLinkState.NEGOTIATING_MTU, message = "协商 BLE MTU")
        val accepted = gatt.requestMtu(MAX_REQUESTED_MTU)
        Log.i(TAG, "requestMtu requested=$MAX_REQUESTED_MTU accepted=$accepted")
        BleDiagnostics.i(TAG, "requestMtu requested=$MAX_REQUESTED_MTU accepted=$accepted")
        if (!accepted) {
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
        Log.i(TAG, "local TX notification enabled; writing CCCD=${descriptor.uuid}")
        BleDiagnostics.i(TAG, "local TX notification enabled; writing CCCD=${descriptor.uuid}")
        updateState(linkState = BleLinkState.SUBSCRIBING, message = "订阅 TX Notify，必要时触发配对")
        enqueue(DescriptorWrite(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE))
    }

    private fun onNotification(callbackGatt: BluetoothGatt, value: ByteArray) {
        mainHandler.post {
            if (!isCurrentGatt(callbackGatt)) return@post
            val event = codec.pushDeviceFrame(value, android.os.SystemClock.elapsedRealtime())
            Log.i(TAG, "TX notify received bytes=${value.size} event=${event::class.simpleName}")
            BleDiagnostics.i(TAG, "TX notify received bytes=${value.size} event=${event::class.simpleName}")
            session.onDeviceEvent(event)
            if (event is com.moto.gps.protocol.NativeBleEvent.DeviceMessage &&
                event.ackRequested && event.sequence != 0
            ) {
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
            val inFlight = if (activeOperation != null) 1 else 0
            if (inFlight + pendingOperations.size + frames.size > MAX_PENDING_OPERATIONS) {
                Log.e(
                    TAG,
                    "GATT queue overflow pending=${pendingOperations.size} active=$inFlight " +
                        "incoming=${frames.size} withResponse=$withResponse",
                )
                abortGatt("GATT 发送队列溢出，已主动断开以重新握手")
                return@post
            }
            Log.i(
                TAG,
                "enqueue frames count=${frames.size} bytes=${frames.sumOf { it.size }} " +
                    "withResponse=$withResponse queueBefore=${pendingOperations.size} " +
                    "active=${activeOperation?.description}",
            )
            BleDiagnostics.i(
                TAG,
                "enqueue frames count=${frames.size} bytes=${frames.sumOf { it.size }} " +
                    "withResponse=$withResponse queueBefore=${pendingOperations.size} " +
                    "active=${activeOperation?.description}",
            )
            frames.forEach { pendingOperations.add(CharacteristicWrite(characteristic, it, type)) }
            pumpQueue()
        }
    }

    private fun enqueue(operation: GattOperation) {
        val inFlight = if (activeOperation == null) 0 else 1
        if (inFlight + pendingOperations.size + 1 > MAX_PENDING_OPERATIONS) {
            Log.e(TAG, "GATT queue overflow operation=${operation.description}")
            abortGatt("GATT 操作队列溢出，已主动断开以重新握手")
            return
        }
        Log.i(TAG, "enqueue operation=${operation.description} queueBefore=${pendingOperations.size}")
        BleDiagnostics.i(TAG, "enqueue operation=${operation.description} queueBefore=${pendingOperations.size}")
        pendingOperations.add(operation)
        pumpQueue()
    }

    private fun pumpQueue() {
        if (activeOperation != null) {
            Log.d(TAG, "GATT queue blocked active=${activeOperation?.description} pending=${pendingOperations.size}")
            return
        }
        if (pendingOperations.isEmpty()) return
        val operation = pendingOperations.removeFirst()
        val currentGatt = gatt ?: return abortGatt("GATT 已断开，清理发送队列")
        activeOperation = operation
        val operationId = ++activeOperationId
        activeOperationStartedAtMs = android.os.SystemClock.elapsedRealtime()
        Log.i(
            TAG,
            "GATT op start id=$operationId ${operation.description} pending=${pendingOperations.size}",
        )
        BleDiagnostics.i(
            TAG,
            "GATT op start id=$operationId ${operation.description} pending=${pendingOperations.size}",
        )
        val result = try {
            operation.start(currentGatt)
        } catch (error: RuntimeException) {
            Log.e(TAG, "GATT op start threw id=$operationId ${operation.description}", error)
            GattStartResult(accepted = false, statusCode = GATT_START_REJECTED)
        }
        Log.i(
            TAG,
            "GATT op accepted id=$operationId accepted=${result.accepted} " +
                "status=${result.statusCode} ${operation.description}",
        )
        if (!result.accepted) {
            activeOperation = null
            handleGattFailure(result.statusCode, "immediate start rejected: ${operation.description}")
            return
        }
        if (!operation.awaitsCallback) {
            Log.i(
                TAG,
                "GATT op complete id=$operationId callback=not-required " +
                    "${operation.description} pending=${pendingOperations.size}",
            )
            BleDiagnostics.i(
                TAG,
                "GATT op complete id=$operationId callback=not-required " +
                    "${operation.description} pending=${pendingOperations.size}",
            )
            activeOperation = null
            pumpQueue()
            return
        }
        val timeout = Runnable {
            if (activeOperationId != operationId || activeOperation !== operation) return@Runnable
            val elapsed = android.os.SystemClock.elapsedRealtime() - activeOperationStartedAtMs
            Log.e(
                TAG,
                "GATT op timeout id=$operationId elapsed=${elapsed}ms " +
                    "${operation.description} pending=${pendingOperations.size}",
            )
            abortGatt("GATT 操作超时：${operation.description}，已断开并等待重新连接")
        }
        operationTimeoutRunnable = timeout
        mainHandler.postDelayed(timeout, GATT_OPERATION_TIMEOUT_MS)
    }

    private fun completeOperation(status: Int, callbackObject: Any) {
        val operation = activeOperation
        if (operation == null) {
            Log.w(TAG, "unexpected GATT operation callback status=$status object=${callbackObject::class.simpleName}")
            return
        }
        operationTimeoutRunnable?.let(mainHandler::removeCallbacks)
        operationTimeoutRunnable = null
        val elapsed = android.os.SystemClock.elapsedRealtime() - activeOperationStartedAtMs
        Log.i(
            TAG,
            "GATT op complete id=$activeOperationId status=$status elapsed=${elapsed}ms " +
                "${operation.description} pending=${pendingOperations.size}",
        )
        BleDiagnostics.i(
            TAG,
            "GATT op complete id=$activeOperationId status=$status elapsed=${elapsed}ms " +
                "${operation.description} pending=${pendingOperations.size}",
        )
        activeOperation = null
        if (status == GATT_SUCCESS) {
            pumpQueue()
            if (callbackObject is BluetoothGattDescriptor && callbackObject.uuid == MotoBleUuids.cccd) {
                updateState(linkState = BleLinkState.HANDSHAKING, message = "TX 已订阅，等待设备 Ready")
                session.start(frameSize)
            }
        } else {
            handleGattFailure(status, "callback: ${operation.description}")
        }
    }

    private fun handleGattFailure(status: Int, detail: String) {
        Log.e(TAG, "GATT operation failure status=$status detail=$detail")
        BleDiagnostics.e(TAG, "GATT operation failure status=$status detail=$detail")
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
            abortGatt("GATT 操作失败：status=$status；$detail")
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

    private fun closeGattOnly(expectedGatt: BluetoothGatt? = null) {
        val currentGatt = gatt
        if (expectedGatt != null && currentGatt !== expectedGatt) {
            // A delayed callback from an older connection must never close the
            // replacement GATT instance. It is safe to close only that stale
            // callback object.
            expectedGatt.close()
            return
        }
        operationTimeoutRunnable?.let(mainHandler::removeCallbacks)
        operationTimeoutRunnable = null
        if (pendingOperations.isNotEmpty() || activeOperation != null) {
            Log.i(
                TAG,
                "clear GATT queue pending=${pendingOperations.size} active=${activeOperation?.description}",
            )
        }
        pendingOperations.clear()
        activeOperation = null
        activeOperationStartedAtMs = 0L
        currentGatt?.close()
        gatt = null
        rxCharacteristic = null
        txCharacteristic = null
        authRetryPending = false
    }

    private fun isCurrentGatt(callbackGatt: BluetoothGatt): Boolean {
        if (!closed && gatt === callbackGatt) return true
        if (closed || gatt !== callbackGatt) {
            Log.w(TAG, "ignoring stale GATT callback address=${callbackGatt.device.address}")
            callbackGatt.close()
        }
        return false
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
        Log.e(TAG, "BLE failure: $message")
        BleDiagnostics.e(TAG, "BLE failure: $message")
        updateState(linkState = BleLinkState.ERROR, protocolReady = false, message = message)
    }

    private fun abortGatt(message: String) {
        Log.e(
            TAG,
            "abort GATT: $message pending=${pendingOperations.size} active=${activeOperation?.description}",
        )
        BleDiagnostics.e(
            TAG,
            "abort GATT: $message pending=${pendingOperations.size} active=${activeOperation?.description}",
        )
        session.stop()
        val currentGatt = gatt
        try {
            currentGatt?.disconnect()
        } catch (error: RuntimeException) {
            Log.w(TAG, "disconnect during abort failed", error)
        }
        closeGattOnly(currentGatt)
        updateState(linkState = BleLinkState.ERROR, protocolReady = false, message = message)
    }
}

private fun writeTypeName(writeType: Int): String = when (writeType) {
    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT -> "with-response"
    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE -> "without-response"
    BluetoothGattCharacteristic.WRITE_TYPE_SIGNED -> "signed"
    else -> "unknown($writeType)"
}
