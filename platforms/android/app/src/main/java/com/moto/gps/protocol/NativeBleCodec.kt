package com.moto.gps.protocol

import org.json.JSONObject

/**
 * JNI owner for the shared C++ BLE protocol. Kotlin never recreates the v1
 * frame header, CRC, payload layouts, or fragment rules.
 */
class NativeBleCodec(maximumFrameSize: Int = 20) : AutoCloseable {
    private var handle: Long = nativeCreate(maximumFrameSize)

    fun setMaximumFrameSize(value: Int) {
        check(handle != 0L) { "codec is closed" }
        nativeSetMaximumFrameSize(handle, value)
    }

    fun resetInboundState() {
        check(handle != 0L) { "codec is closed" }
        nativeResetInboundState(handle)
    }

    fun encodeConnectionStatus(state: ConnectionState, sessionId: UInt): List<ByteArray> {
        check(handle != 0L) { "codec is closed" }
        return nativeEncodeConnectionStatus(handle, state.wireValue, sessionId.toInt())
            ?.toList()
            ?: error("shared BLE codec rejected ConnectionStatus")
    }

    fun encodeHeartbeat(sessionId: UInt, monotonicMs: UInt): List<ByteArray> {
        check(handle != 0L) { "codec is closed" }
        return nativeEncodeHeartbeat(handle, sessionId.toInt(), monotonicMs.toInt())
            ?.toList()
            ?: error("shared BLE codec rejected Heartbeat")
    }

    fun encodeAck(sequence: Int, status: Int = 0, commandId: Int = 0): List<ByteArray> {
        check(handle != 0L) { "codec is closed" }
        return nativeEncodeAck(handle, sequence, status, commandId)
            ?.toList()
            ?: error("shared BLE codec rejected Ack")
    }

    fun pushDeviceFrame(frame: ByteArray, receivedAtMs: Long): NativeBleEvent {
        check(handle != 0L) { "codec is closed" }
        val json = nativePushDeviceFrame(handle, frame, receivedAtMs)
            ?: return NativeBleEvent.Error("native codec returned no event")
        return NativeBleEvent.fromJson(json)
    }

    fun goldenVectorCheck(): Boolean = nativeGoldenVectorCheck()

    fun coordinateSanityCheck(): Boolean = nativeCoordinateSanityCheck()

    override fun close() {
        if (handle != 0L) {
            nativeDestroy(handle)
            handle = 0L
        }
    }

    companion object {
        init {
            System.loadLibrary("moto_android_jni")
        }

        private external fun nativeCreate(maximumFrameSize: Int): Long
        private external fun nativeDestroy(handle: Long)
        private external fun nativeSetMaximumFrameSize(handle: Long, maximumFrameSize: Int)
        private external fun nativeResetInboundState(handle: Long)
        private external fun nativeEncodeConnectionStatus(
            handle: Long,
            state: Int,
            sessionId: Int,
        ): Array<ByteArray>?

        private external fun nativeEncodeHeartbeat(
            handle: Long,
            sessionId: Int,
            monotonicMs: Int,
        ): Array<ByteArray>?

        private external fun nativeEncodeAck(
            handle: Long,
            acknowledgedSequence: Int,
            status: Int,
            commandId: Int,
        ): Array<ByteArray>?

        private external fun nativePushDeviceFrame(
            handle: Long,
            frame: ByteArray,
            receivedAtMs: Long,
        ): String?

        private external fun nativeGoldenVectorCheck(): Boolean
        private external fun nativeCoordinateSanityCheck(): Boolean
    }
}

enum class ConnectionState(val wireValue: Int) {
    STARTING(0),
    READY(1),
    DEGRADED(2),
    CLOSING(3),
}

sealed interface NativeBleEvent {
    data class ConnectionStatus(
        val role: Int,
        val state: Int,
        val capabilities: Long,
        val sessionId: UInt,
        val maximumFrameSize: Int,
        val heartbeatIntervalMs: Int,
        val sequence: Int,
    ) : NativeBleEvent

    data class Heartbeat(
        val sessionId: UInt,
        val monotonicMs: UInt,
    ) : NativeBleEvent

    data class Acknowledgement(
        val acknowledgedSequence: Int,
        val status: Int,
        val commandId: Int,
    ) : NativeBleEvent

    /** A valid message whose payload is not needed by the first Android stage. */
    data class DeviceMessage(
        val type: Int,
        val sequence: Int,
        val ackRequested: Boolean,
        val ackCommandId: Int = 0,
    ) : NativeBleEvent

    data class Partial(val sequence: Int) : NativeBleEvent
    data class Duplicate(val sequence: Int) : NativeBleEvent
    data class Error(val message: String) : NativeBleEvent

    companion object {
        fun fromJson(json: String): NativeBleEvent {
            return runCatching {
                val value = JSONObject(json)
                when (value.optString("kind")) {
                    "partial" -> Partial(value.optInt("sequence"))
                    "duplicate" -> Duplicate(value.optInt("sequence"))
                    "error" -> Error(value.optString("error", "unknown"))
                    "complete" -> {
                        val type = value.optInt("type", -1)
                        when {
                            type == 0x01 -> ConnectionStatus(
                            role = value.optInt("role"),
                            state = value.optInt("state"),
                            capabilities = value.optLong("capabilities"),
                            sessionId = value.optLong("sessionId").toUInt(),
                            maximumFrameSize = value.optInt("maxFrameSize"),
                            heartbeatIntervalMs = value.optInt("heartbeatIntervalMs"),
                            sequence = value.optInt("sequence"),
                            )

                            type == 0x02 -> Heartbeat(
                            sessionId = value.optLong("sessionId").toUInt(),
                            monotonicMs = value.optLong("monotonicMs").toUInt(),
                            )

                            type == 0x03 -> Acknowledgement(
                            acknowledgedSequence = value.optInt("acknowledgedSequence"),
                            status = value.optInt("status"),
                            commandId = value.optInt("commandId"),
                            )

                            else -> DeviceMessage(
                                type = type,
                                sequence = value.optInt("sequence"),
                                ackRequested = value.optBoolean("ackRequested"),
                                ackCommandId = value.optInt("commandId"),
                            )
                        }
                    }

                    else -> Error("unknown native event kind")
                }
            }.getOrElse { Error("invalid native event: ${it.message}") }
        }
    }
}
