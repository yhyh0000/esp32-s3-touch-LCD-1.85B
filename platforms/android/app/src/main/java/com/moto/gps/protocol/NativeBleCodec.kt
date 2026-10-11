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

    fun encodeNavigationSnapshot(snapshot: NavigationSnapshot): List<ByteArray> {
        check(handle != 0L) { "codec is closed" }
        val values = intArrayOf(
            snapshot.state.wireValue,
            snapshot.network.wireValue,
            snapshot.displayPage.wireValue,
            snapshot.maneuver.wireValue,
            snapshot.traffic.wireValue,
            snapshot.flags,
            snapshot.routeToken.toInt(),
            snapshot.routeGeneration.toInt(),
            snapshot.maneuverId.toInt(),
            snapshot.distanceToManeuverM,
            snapshot.remainingDistanceM,
            snapshot.remainingDurationS,
            snapshot.routeProgressM,
            snapshot.totalDistanceM,
            snapshot.speedDeciKph,
            snapshot.speedLimitKph,
            snapshot.headingCdeg,
            snapshot.accuracyDm,
            snapshot.crossTrackDm,
            snapshot.roundaboutExit,
        )
        return nativeEncodeNavigationSnapshot(handle, values, snapshot.roadName, snapshot.instruction)
            ?.toList()
            ?: error("shared BLE codec rejected NavigationSnapshot")
    }

    fun encodeRouteGeometry(geometry: RouteGeometry): List<ByteArray> {
        check(handle != 0L) { "codec is closed" }
        val header = intArrayOf(
            geometry.coordinateSystem.wireValue,
            geometry.routeToken.toInt(),
            geometry.routeGeneration.toInt(),
            geometry.chunkIndex,
            geometry.chunkCount,
            geometry.firstPointIndex,
            geometry.totalPointCount,
            geometry.viewOrigin.latitudeE6,
            geometry.viewOrigin.longitudeE6,
        )
        val points = geometry.points.flatMap { listOf(it.latitudeE6, it.longitudeE6) }.toIntArray()
        return nativeEncodeRouteGeometry(handle, header, points)
            ?.toList()
            ?: error("shared BLE codec rejected RouteGeometry")
    }

    fun pushDeviceFrame(frame: ByteArray, receivedAtMs: Long): NativeBleEvent {
        check(handle != 0L) { "codec is closed" }
        val json = nativePushDeviceFrame(handle, frame, receivedAtMs)
            ?: return NativeBleEvent.Error("native codec returned no event")
        return NativeBleEvent.fromJson(json)
    }

    fun goldenVectorCheck(): Boolean = nativeGoldenVectorCheck()

    fun coordinateSanityCheck(): Boolean = nativeCoordinateSanityCheck()

    companion object {
        init {
            System.loadLibrary("moto_android_jni")
        }

        fun routeToken(routeId: String): UInt = nativeRouteToken(routeId).toUInt()

        fun wgs84ToGcj02(longitudeDeg: Double, latitudeDeg: Double): Pair<Double, Double> {
            val converted = nativeWgs84ToGcj02(longitudeDeg, latitudeDeg)
            require(converted.size == 2) { "shared coordinate conversion returned invalid data" }
            return converted[0] to converted[1]
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

        private external fun nativeEncodeNavigationSnapshot(
            handle: Long,
            values: IntArray,
            roadName: String,
            instruction: String,
        ): Array<ByteArray>?

        private external fun nativeEncodeRouteGeometry(
            handle: Long,
            header: IntArray,
            points: IntArray,
        ): Array<ByteArray>?

        private external fun nativePushDeviceFrame(
            handle: Long,
            frame: ByteArray,
            receivedAtMs: Long,
        ): String?

        private external fun nativeGoldenVectorCheck(): Boolean
        private external fun nativeCoordinateSanityCheck(): Boolean
        private external fun nativeRouteToken(routeId: String): Int
        private external fun nativeWgs84ToGcj02(longitudeDeg: Double, latitudeDeg: Double): DoubleArray
    }

    override fun close() {
        if (handle != 0L) {
            nativeDestroy(handle)
            handle = 0L
        }
    }
}

enum class ConnectionState(val wireValue: Int) {
    STARTING(0),
    READY(1),
    DEGRADED(2),
    CLOSING(3),
}

enum class NavigationState(val wireValue: Int) {
    IDLE(0),
    ACQUIRING(1),
    PLANNING(2),
    NAVIGATING(3),
    REROUTING(4),
    ARRIVED(5),
}

enum class NetworkState(val wireValue: Int) {
    OFFLINE(0),
    CONNECTING(1),
    ONLINE(2),
}

enum class DisplayPage(val wireValue: Int) {
    NAVIGATION(0),
    SPEED(1),
    COMPASS(2),
    MUSIC(3),
}

enum class Maneuver(val wireValue: Int) {
    UNKNOWN(0),
    CONTINUE(1),
    SLIGHT_LEFT(2),
    LEFT(3),
    SHARP_LEFT(4),
    U_TURN_LEFT(5),
    SLIGHT_RIGHT(6),
    RIGHT(7),
    SHARP_RIGHT(8),
    U_TURN_RIGHT(9),
    ROUNDABOUT(10),
    EXIT(11),
    ARRIVE(12),
}

enum class TrafficLevel(val wireValue: Int) {
    UNKNOWN(0),
    FREE_FLOW(1),
    SLOW(2),
    CONGESTED(3),
    SEVERE(4),
}

enum class CoordinateSystem(val wireValue: Int) {
    WGS84(1),
    GCJ02(2),
}

data class GeoPointE6(
    val latitudeE6: Int,
    val longitudeE6: Int,
)

data class NavigationSnapshot(
    val state: NavigationState = NavigationState.IDLE,
    val network: NetworkState = NetworkState.OFFLINE,
    val displayPage: DisplayPage = DisplayPage.NAVIGATION,
    val maneuver: Maneuver = Maneuver.UNKNOWN,
    val traffic: TrafficLevel = TrafficLevel.UNKNOWN,
    val flags: Int = 0,
    val routeToken: UInt = 0u,
    val routeGeneration: UInt = 0u,
    val maneuverId: UInt = 0u,
    val distanceToManeuverM: Int = 0,
    val remainingDistanceM: Int = 0,
    val remainingDurationS: Int = 0,
    val routeProgressM: Int = 0,
    val totalDistanceM: Int = 0,
    val speedDeciKph: Int = 0,
    val speedLimitKph: Int = 0,
    val headingCdeg: Int = 0,
    val accuracyDm: Int = 0,
    val crossTrackDm: Int = 0,
    val roundaboutExit: Int = 0,
    val roadName: String = "",
    val instruction: String = "",
)

data class RouteGeometry(
    val coordinateSystem: CoordinateSystem = CoordinateSystem.GCJ02,
    val routeToken: UInt,
    val routeGeneration: UInt,
    val chunkIndex: Int = 0,
    val chunkCount: Int = 1,
    val firstPointIndex: Int = 0,
    val totalPointCount: Int,
    val viewOrigin: GeoPointE6,
    val points: List<GeoPointE6>,
)

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
