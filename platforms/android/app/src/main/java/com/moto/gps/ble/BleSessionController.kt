package com.moto.gps.ble

import android.os.SystemClock
import android.util.Log
import com.moto.gps.protocol.ConnectionState
import com.moto.gps.protocol.NativeBleCodec
import com.moto.gps.protocol.NativeBleEvent
import java.security.SecureRandom

private const val DEVICE_ROLE = 2
private const val DEVICE_READY = 1
private const val HEARTBEAT_DEFAULT_MS = 1_000L

enum class ProtocolSessionState {
    IDLE,
    WAITING_DEVICE_READY,
    WAITING_FINAL_DEVICE_READY,
    READY,
    FAILED,
}

/**
 * State machine for the v1 protocol handshake. BLE callbacks only feed events
 * here; no GATT write is performed directly from a notification callback.
 */
class BleSessionController(
    private val codec: NativeBleCodec,
    private val sendFrames: (List<ByteArray>, Boolean) -> Unit,
    private val onStateChanged: (ProtocolSessionState, String) -> Unit,
    private val nowElapsedMs: () -> Long = { SystemClock.elapsedRealtime() },
) {
    private companion object {
        const val TAG = "MotoBleSession"
    }

    private val random = SecureRandom()
    private var sessionId: UInt = 0u
    private var sessionStartedAtMs = 0L
    private var heartbeatIntervalMs = HEARTBEAT_DEFAULT_MS
    private var nextHeartbeatAtMs = Long.MAX_VALUE
    private var lastDeviceFrameAtMs = 0L
    private var state = ProtocolSessionState.IDLE
    private var localMaximumFrameSize = 20

    fun start(localMaximumFrameSize: Int) {
        codec.resetInboundState()
        this.localMaximumFrameSize = localMaximumFrameSize.coerceIn(20, 512)
        codec.setMaximumFrameSize(this.localMaximumFrameSize)
        sessionId = nextSessionId()
        sessionStartedAtMs = nowElapsedMs()
        heartbeatIntervalMs = HEARTBEAT_DEFAULT_MS
        nextHeartbeatAtMs = Long.MAX_VALUE
        lastDeviceFrameAtMs = sessionStartedAtMs
        state = ProtocolSessionState.WAITING_DEVICE_READY
        Log.i(
            TAG,
            "start session=${sessionId.toString(16)} frameSize=${this.localMaximumFrameSize} " +
                "heartbeatMs=$heartbeatIntervalMs",
        )
        BleDiagnostics.i(
            TAG,
            "start session=${sessionId.toString(16)} frameSize=${this.localMaximumFrameSize} " +
                "heartbeatMs=$heartbeatIntervalMs",
        )
        onStateChanged(state, "已订阅 TX，发送 Phone Starting")
        sendFrames(
            codec.encodeConnectionStatus(ConnectionState.STARTING, sessionId),
            true,
        )
    }

    fun onDeviceEvent(event: NativeBleEvent) {
        if (event !is NativeBleEvent.Error) {
            lastDeviceFrameAtMs = nowElapsedMs()
        }
        Log.i(
            TAG,
            "device event=${event::class.simpleName} state=$state " +
                "lastDeviceFrameAtMs=$lastDeviceFrameAtMs",
        )
        BleDiagnostics.i(
            TAG,
            "device event=${event::class.simpleName} state=$state " +
                "lastDeviceFrameAtMs=$lastDeviceFrameAtMs",
        )
        when (event) {
            is NativeBleEvent.ConnectionStatus -> onConnectionStatus(event)
            is NativeBleEvent.Heartbeat -> {
                if (event.sessionId == sessionId) {
                    nextHeartbeatAtMs = nowElapsedMs() + heartbeatIntervalMs
                }
            }

            is NativeBleEvent.Acknowledgement -> Unit
            is NativeBleEvent.DeviceMessage -> Unit
            is NativeBleEvent.Partial, is NativeBleEvent.Duplicate -> Unit
            is NativeBleEvent.Error -> fail("设备帧错误：${event.message}")
        }
    }

    fun tick() {
        if (state != ProtocolSessionState.READY || sessionId == 0u) return
        val now = nowElapsedMs()
        val deviceSilenceMs = now - lastDeviceFrameAtMs
        if (deviceSilenceMs >= heartbeatIntervalMs * 3L) {
            Log.e(
                TAG,
                "device heartbeat timeout silenceMs=$deviceSilenceMs " +
                    "heartbeatMs=$heartbeatIntervalMs session=${sessionId.toString(16)}",
            )
            BleDiagnostics.e(
                TAG,
                "device heartbeat timeout silenceMs=$deviceSilenceMs " +
                    "heartbeatMs=$heartbeatIntervalMs session=${sessionId.toString(16)}",
            )
            fail("设备心跳超时：${deviceSilenceMs}ms 未收到有效帧")
            return
        }
        if (now < nextHeartbeatAtMs) return
        val elapsed = (now - sessionStartedAtMs).coerceAtLeast(0L).toUInt()
        // Heartbeats are the high-frequency liveness path. The protocol uses
        // Write Without Response for them; the GATT queue completes that kind
        // of operation from the immediate Android return value, so a missing
        // onCharacteristicWrite callback cannot block subsequent heartbeats.
        Log.i(
            TAG,
            "send heartbeat session=${sessionId.toString(16)} elapsedMs=$elapsed " +
                "nextInMs=$heartbeatIntervalMs",
        )
        BleDiagnostics.i(
            TAG,
            "send heartbeat session=${sessionId.toString(16)} elapsedMs=$elapsed " +
                "nextInMs=$heartbeatIntervalMs",
        )
        sendFrames(codec.encodeHeartbeat(sessionId, elapsed), false)
        nextHeartbeatAtMs = now + heartbeatIntervalMs
    }

    fun stop() {
        Log.i(TAG, "stop session=${sessionId.toString(16)} state=$state")
        BleDiagnostics.i(TAG, "stop session=${sessionId.toString(16)} state=$state")
        state = ProtocolSessionState.IDLE
        sessionId = 0u
        nextHeartbeatAtMs = Long.MAX_VALUE
        lastDeviceFrameAtMs = 0L
        codec.resetInboundState()
        onStateChanged(state, "连接已断开")
    }

    private fun onConnectionStatus(event: NativeBleEvent.ConnectionStatus) {
        Log.i(
            TAG,
            "device status role=${event.role} state=${event.state} session=${event.sessionId.toString(16)} " +
                "maxFrame=${event.maximumFrameSize} heartbeatMs=${event.heartbeatIntervalMs}",
        )
        BleDiagnostics.i(
            TAG,
            "device status role=${event.role} state=${event.state} session=${event.sessionId.toString(16)} " +
                "maxFrame=${event.maximumFrameSize} heartbeatMs=${event.heartbeatIntervalMs}",
        )
        if (event.role != DEVICE_ROLE || event.sessionId != sessionId) {
            fail("设备握手会话不匹配")
            return
        }
        if (event.state != DEVICE_READY) {
            fail("设备未进入 Ready，state=${event.state}")
            return
        }

        heartbeatIntervalMs = event.heartbeatIntervalMs
            .coerceIn(250, 15_000)
            .toLong()
        codec.setMaximumFrameSize(
            event.maximumFrameSize.coerceIn(20, localMaximumFrameSize),
        )

        when (state) {
            ProtocolSessionState.WAITING_DEVICE_READY -> {
                state = ProtocolSessionState.WAITING_FINAL_DEVICE_READY
                onStateChanged(state, "收到设备 Ready，发送 Phone Ready")
                sendFrames(
                    codec.encodeConnectionStatus(ConnectionState.READY, sessionId),
                    true,
                )
            }

            ProtocolSessionState.WAITING_FINAL_DEVICE_READY -> {
                state = ProtocolSessionState.READY
                nextHeartbeatAtMs = nowElapsedMs() + heartbeatIntervalMs
                onStateChanged(state, "协议握手完成，设备可接收导航状态")
            }

            ProtocolSessionState.READY -> {
                nextHeartbeatAtMs = nowElapsedMs() + heartbeatIntervalMs
            }

            else -> fail("握手状态异常：$state")
        }
    }

    private fun nextSessionId(): UInt {
        var value = random.nextInt().toUInt()
        if (value == 0u) value = 1u
        return value
    }

    private fun fail(message: String) {
        if (state == ProtocolSessionState.FAILED) return
        Log.e(TAG, "session failed state=$state message=$message")
        BleDiagnostics.e(TAG, "session failed state=$state message=$message")
        state = ProtocolSessionState.FAILED
        nextHeartbeatAtMs = Long.MAX_VALUE
        onStateChanged(state, message)
    }
}
