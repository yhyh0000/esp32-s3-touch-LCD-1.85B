package com.moto.gps.navigation

import android.content.Context
import com.moto.gps.ble.BleGattClient
import com.moto.gps.protocol.CoordinateSystem
import com.moto.gps.protocol.DisplayPage
import com.moto.gps.protocol.Maneuver
import com.moto.gps.protocol.NavigationSnapshot
import com.moto.gps.protocol.NavigationState
import com.moto.gps.protocol.NativeBleCodec
import com.moto.gps.protocol.NetworkState
import com.moto.gps.protocol.RouteGeometry
import com.moto.gps.protocol.TrafficLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

private const val HAS_DESTINATION = 1
private const val HAS_FIX = 1 shl 1
private const val GNSS_STALE = 1 shl 2
private const val OFF_ROUTE = 1 shl 3
private const val HAS_NEXT_MANEUVER = 1 shl 4
private const val HAS_ROUTE_VIEW = 1 shl 7

data class NavigationUiState(
    val gatewayUrl: String = "",
    val query: String = "",
    val places: List<PlaceResult> = emptyList(),
    val routes: List<RouteBundle> = emptyList(),
    val selectedRouteId: String? = null,
    val active: Boolean = false,
    val message: String = "尚未开始导航",
    val fix: LocationFix? = null,
)

/** Coordinates gateway, platform location and the existing BLE session. */
class NavigationController(
    context: Context,
    private val ble: BleGattClient,
    private val scope: CoroutineScope,
) : AutoCloseable {
    private val gateway = GatewayClient()
    private val location = LocationProvider(context)
    private val _state = MutableStateFlow(NavigationUiState())
    val state: StateFlow<NavigationUiState> = _state.asStateFlow()
    private var locationJob: Job? = null
    private var requestId = 1u
    private var selectedRoute: RouteBundle? = null
    private var sentWindowStart = -1

    init {
        locationJob = scope.launch {
            location.fix.collect { fix ->
                _state.value = _state.value.copy(fix = fix)
                if (_state.value.active && fix != null) sendNavigation(fix)
            }
        }
    }

    fun setGatewayUrl(value: String) {
        _state.value = _state.value.copy(gatewayUrl = value)
    }

    fun setQuery(value: String) {
        _state.value = _state.value.copy(query = value)
    }

    fun startLocation() {
        location.start().onSuccess {
            _state.value = _state.value.copy(message = "已启动定位，等待有效 GNSS 位置")
        }.onFailure {
            _state.value = _state.value.copy(message = "定位失败：${it.message}")
        }
    }

    fun searchPlaces() {
        val current = _state.value
        scope.launch {
            _state.value = current.copy(message = "正在搜索目的地", places = emptyList())
            runCatching {
                gateway.searchPlaces(
                    current.gatewayUrl,
                    current.query,
                    current.fix?.let { Wgs84Point(it.longitudeDeg, it.latitudeDeg) },
                )
            }.onSuccess { places ->
                _state.value = _state.value.copy(
                    places = places,
                    message = if (places.isEmpty()) "没有找到目的地" else "请选择目的地",
                )
            }.onFailure { error ->
                _state.value = _state.value.copy(message = "搜索失败：${error.message}")
            }
        }
    }

    fun planRoute(place: PlaceResult) {
        val current = _state.value
        val origin = current.fix?.let { Wgs84Point(it.longitudeDeg, it.latitudeDeg) }
        if (origin == null) {
            _state.value = current.copy(message = "还没有有效定位，请先启动定位并等待 GNSS")
            startLocation()
            return
        }
        scope.launch {
            _state.value = current.copy(message = "正在请求候选路线", routes = emptyList())
            runCatching {
                gateway.routeOptions(
                    baseUrl = current.gatewayUrl,
                    requestId = requestId++,
                    origin = origin,
                    destination = place.location,
                    destinationPoiId = place.id,
                )
            }.onSuccess { routes ->
                selectedRoute = routes.firstOrNull()
                _state.value = _state.value.copy(
                    routes = routes,
                    selectedRouteId = routes.firstOrNull()?.routeId,
                    message = if (routes.isEmpty()) "网关没有可用路线" else "已获得候选路线，默认选择第一条",
                )
            }.onFailure { error ->
                _state.value = _state.value.copy(message = "路线请求失败：${error.message}")
            }
        }
    }

    fun selectRoute(route: RouteBundle) {
        selectedRoute = route
        _state.value = _state.value.copy(
            selectedRouteId = route.routeId,
            message = "已选择路线：${route.totalDistanceM} 米，约 ${route.totalDurationS} 秒",
        )
    }

    fun startNavigation() {
        val route = selectedRoute
        if (route == null) {
            _state.value = _state.value.copy(message = "请先搜索并选择路线")
            return
        }
        if (!ble.uiState.value.protocolReady) {
            _state.value = _state.value.copy(message = "圆屏尚未处于 READY，不能发送导航")
            return
        }
        _state.value = _state.value.copy(active = true, message = "导航已开始，等待位置更新")
        sentWindowStart = -1
        location.start().onFailure {
            _state.value = _state.value.copy(message = "导航定位失败：${it.message}")
        }
        _state.value.fix?.let(::sendNavigation)
    }

    fun stopNavigation() {
        _state.value = _state.value.copy(active = false, message = "导航已结束")
        location.stop()
        selectedRoute?.let { route ->
            val token = NativeBleCodec.routeToken(route.routeId)
            ble.sendNavigationSnapshot(
                NavigationSnapshot(
                    state = NavigationState.IDLE,
                    network = NetworkState.ONLINE,
                    routeToken = token,
                    routeGeneration = 1u,
                    totalDistanceM = route.totalDistanceM,
                ),
            )
        }
    }

    override fun close() {
        locationJob?.cancel()
        location.close()
    }

    private fun sendNavigation(fix: LocationFix) {
        val route = selectedRoute ?: return
        val now = System.currentTimeMillis()
        val stale = now - fix.timestampMs > 5_000L
        val converted = runCatching {
            NativeBleCodec.wgs84ToGcj02(fix.longitudeDeg, fix.latitudeDeg)
        }.getOrElse {
            _state.value = _state.value.copy(message = "坐标转换失败：${it.message}")
            return
        }
        val currentPoint = RoutePoint(converted.first, converted.second)
        val nearest = nearestPoint(route.polyline, currentPoint)
        val routeToken = NativeBleCodec.routeToken(route.routeId)
        if (nearest.windowStart != sentWindowStart) {
            sentWindowStart = nearest.windowStart
            val window = route.polyline.drop(nearest.windowStart).take(24)
            if (window.size >= 2) {
                ble.sendRouteGeometry(
                    RouteGeometry(
                        coordinateSystem = CoordinateSystem.GCJ02,
                        routeToken = routeToken,
                        routeGeneration = 1u,
                        totalPointCount = window.size,
                        viewOrigin = GeoPointE6(
                            currentPoint.latitudeDeg.toE6(),
                            currentPoint.longitudeDeg.toE6(),
                        ),
                        points = window.map { GeoPointE6(it.latitudeDeg.toE6(), it.longitudeDeg.toE6()) },
                    ),
                )
            }
        }
        val progress = nearest.progressM.coerceIn(0, route.totalDistanceM)
        val next = route.maneuvers.firstOrNull { it.routeOffsetM >= progress }
        var flags = HAS_DESTINATION or HAS_FIX or HAS_ROUTE_VIEW
        if (stale) flags = flags or GNSS_STALE
        if (nearest.crossTrackM > 50) flags = flags or OFF_ROUTE
        if (next != null) flags = flags or HAS_NEXT_MANEUVER
        val snapshot = NavigationSnapshot(
            state = NavigationState.NAVIGATING,
            network = NetworkState.ONLINE,
            maneuver = next?.type.toManeuver(),
            traffic = TrafficLevel.UNKNOWN,
            flags = flags,
            routeToken = routeToken,
            routeGeneration = 1u,
            maneuverId = next?.id ?: 0u,
            distanceToManeuverM = ((next?.routeOffsetM ?: progress) - progress).coerceAtLeast(0),
            remainingDistanceM = (route.totalDistanceM - progress).coerceAtLeast(0),
            remainingDurationS = ((route.totalDurationS.toDouble() *
                (route.totalDistanceM - progress).coerceAtLeast(0) /
                max(1, route.totalDistanceM)).roundToInt()).coerceAtLeast(0),
            routeProgressM = progress,
            totalDistanceM = route.totalDistanceM,
            speedDeciKph = (fix.speedMps * 36f).roundToInt().coerceIn(0, 65_535),
            headingCdeg = ((fix.bearingDeg * 100f).roundToInt() % 36_000 + 36_000) % 36_000,
            accuracyDm = (fix.accuracyM * 10f).roundToInt().coerceIn(0, 65_535),
            crossTrackDm = (nearest.crossTrackM * 10).coerceIn(0, 65_535),
            roadName = next?.roadName.orEmpty(),
            instruction = next?.instruction.orEmpty(),
        )
        ble.sendNavigationSnapshot(snapshot)
        _state.value = _state.value.copy(
            message = if (stale) "导航中：定位数据已过期，等待新位置" else "导航中：已发送位置与路线状态",
        )
    }

    private data class NearestPoint(
        val index: Int,
        val windowStart: Int,
        val progressM: Int,
        val crossTrackM: Int,
    )

    private fun nearestPoint(polyline: List<RoutePoint>, current: RoutePoint): NearestPoint {
        var bestIndex = 0
        var bestDistance = Double.MAX_VALUE
        var progress = 0.0
        var bestProgress = 0.0
        for (index in polyline.indices) {
            val distance = distanceM(polyline[index], current)
            if (distance < bestDistance) {
                bestDistance = distance
                bestIndex = index
                bestProgress = progress
            }
            if (index + 1 < polyline.size) progress += distanceM(polyline[index], polyline[index + 1])
        }
        return NearestPoint(
            index = bestIndex,
            windowStart = (bestIndex - 1).coerceAtLeast(0).coerceAtMost(max(0, polyline.size - 2)),
            progressM = bestProgress.roundToInt(),
            crossTrackM = bestDistance.roundToInt(),
        )
    }

    private fun distanceM(a: RoutePoint, b: RoutePoint): Double {
        val lat = Math.toRadians((a.latitudeDeg + b.latitudeDeg) / 2.0)
        val dx = Math.toRadians(a.longitudeDeg - b.longitudeDeg) * cos(lat)
        val dy = Math.toRadians(a.latitudeDeg - b.latitudeDeg)
        return 6_371_000.0 * sqrt(dx * dx + dy * dy)
    }
}

private fun Double.toE6(): Int = (this * 1_000_000.0).roundToInt()

private fun String?.toManeuver(): Maneuver = when (this) {
    "continue" -> Maneuver.CONTINUE
    "slight_left" -> Maneuver.SLIGHT_LEFT
    "left" -> Maneuver.LEFT
    "sharp_left" -> Maneuver.SHARP_LEFT
    "u_turn_left" -> Maneuver.U_TURN_LEFT
    "slight_right" -> Maneuver.SLIGHT_RIGHT
    "right" -> Maneuver.RIGHT
    "sharp_right" -> Maneuver.SHARP_RIGHT
    "u_turn_right" -> Maneuver.U_TURN_RIGHT
    "roundabout" -> Maneuver.ROUNDABOUT
    "exit" -> Maneuver.EXIT
    "arrive" -> Maneuver.ARRIVE
    else -> Maneuver.UNKNOWN
}
