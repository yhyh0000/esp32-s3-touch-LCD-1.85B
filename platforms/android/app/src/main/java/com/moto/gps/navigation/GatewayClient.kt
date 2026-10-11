package com.moto.gps.navigation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class Wgs84Point(
    val longitudeDeg: Double,
    val latitudeDeg: Double,
)

data class PlaceResult(
    val id: String,
    val name: String,
    val address: String,
    val location: Wgs84Point,
)

data class RouteManeuver(
    val id: UInt,
    val type: String,
    val routeOffsetM: Int,
    val roadName: String,
    val instruction: String,
    val roundaboutExit: Int,
)

data class RoutePoint(
    val longitudeDeg: Double,
    val latitudeDeg: Double,
)

data class RouteBundle(
    val routeId: String,
    val generatedAtMs: Long,
    val totalDistanceM: Int,
    val totalDurationS: Int,
    val polyline: List<RoutePoint>,
    val maneuvers: List<RouteManeuver>,
)

class GatewayException(message: String) : IOException(message)

/** Provider-neutral HTTP client. AMap credentials never enter this class. */
class GatewayClient {
    suspend fun searchPlaces(
        baseUrl: String,
        keywords: String,
        bias: Wgs84Point?,
    ): List<PlaceResult> = withContext(Dispatchers.IO) {
        val normalizedBaseUrl = requireBaseUrl(baseUrl)
        require(keywords.isNotBlank()) { "请输入目的地" }
        val query = buildString {
            append("keywords=")
            append(java.net.URLEncoder.encode(keywords.trim(), Charsets.UTF_8.name()))
            if (bias != null) {
                append("&longitude_deg=")
                append(bias.longitudeDeg)
                append("&latitude_deg=")
                append(bias.latitudeDeg)
            }
        }
        val body = request("$normalizedBaseUrl/v1/places?$query", "GET", null)
        val places = body.optJSONArray("places") ?: JSONArray()
        buildList {
            for (index in 0 until places.length()) {
                val item = places.getJSONObject(index)
                val location = item.getJSONObject("location")
                add(
                    PlaceResult(
                        id = item.optString("id"),
                        name = item.optString("name"),
                        address = item.optString("address"),
                        location = Wgs84Point(
                            longitudeDeg = location.getDouble("longitude_deg"),
                            latitudeDeg = location.getDouble("latitude_deg"),
                        ),
                    ),
                )
            }
        }
    }

    suspend fun routeOptions(
        baseUrl: String,
        requestId: UInt,
        origin: Wgs84Point,
        destination: Wgs84Point,
        destinationPoiId: String? = null,
        routeMode: String = "driving",
    ): List<RouteBundle> = withContext(Dispatchers.IO) {
        val normalizedBaseUrl = requireBaseUrl(baseUrl)
        val request = JSONObject()
            .put("protocol_version", 1)
            .put("request_id", requestId.toLong())
            .put("route_mode", routeMode)
            .put("origin", pointJson(origin))
            .put("destination", pointJson(destination))
            .put("is_reroute", false)
        if (!destinationPoiId.isNullOrBlank()) request.put("destination_poi_id", destinationPoiId)
        val body = request("$normalizedBaseUrl/v1/route-options", "POST", request.toString())
        val routes = body.optJSONArray("routes")
            ?: throw GatewayException("网关没有返回 routes")
        buildList {
            for (index in 0 until routes.length()) add(parseRoute(routes.getJSONObject(index)))
        }
    }

    private fun parseRoute(json: JSONObject): RouteBundle {
        require(json.optInt("schema_version") == 1) { "不支持的路线 schema" }
        require(json.optString("provider") == "amap") { "路线 provider 不是 amap" }
        require(json.optString("coordinate_system") == "GCJ-02") {
            "路线几何坐标系不是 GCJ-02"
        }
        val polylineJson = json.optJSONArray("polyline")
            ?: throw GatewayException("路线缺少 polyline")
        if (polylineJson.length() < 2) throw GatewayException("路线点数不足")
        val polyline = buildList {
            for (index in 0 until polylineJson.length()) {
                val point = polylineJson.getJSONObject(index)
                add(RoutePoint(point.getDouble("longitude_deg"), point.getDouble("latitude_deg")))
            }
        }
        val maneuversJson = json.optJSONArray("maneuvers") ?: JSONArray()
        val maneuvers = buildList {
            for (index in 0 until maneuversJson.length()) {
                val item = maneuversJson.getJSONObject(index)
                add(
                    RouteManeuver(
                        id = item.getLong("id").toUInt(),
                        type = item.optString("type", "unknown"),
                        routeOffsetM = item.optDouble("route_offset_m").toInt(),
                        roadName = item.optString("road_name"),
                        instruction = item.optString("instruction"),
                        roundaboutExit = item.optInt("roundabout_exit"),
                    ),
                )
            }
        }
        return RouteBundle(
            routeId = json.getString("route_id"),
            generatedAtMs = json.getLong("generated_at_ms"),
            totalDistanceM = json.getDouble("total_distance_m").toInt(),
            totalDurationS = json.getInt("total_duration_s"),
            polyline = polyline,
            maneuvers = maneuvers,
        )
    }

    private fun pointJson(point: Wgs84Point) = JSONObject()
        .put("coordinate_system", "WGS84")
        .put("longitude_deg", point.longitudeDeg)
        .put("latitude_deg", point.latitudeDeg)

    private fun requireBaseUrl(value: String): String {
        val normalized = value.trim().trimEnd('/')
        require(normalized.startsWith("https://") || normalized.startsWith("http://")) {
            "网关地址必须以 http:// 或 https:// 开头"
        }
        return normalized
    }

    private fun request(url: String, method: String, body: String?): JSONObject {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 8_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/json")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        return try {
            if (body != null) connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            val json = runCatching { JSONObject(response) }.getOrElse {
                throw GatewayException("网关返回不是 JSON：HTTP $status")
            }
            if (status !in 200..299) {
                val error = json.optJSONObject("error")
                throw GatewayException(
                    error?.optString("message")?.takeIf { it.isNotBlank() }
                        ?: "网关请求失败：HTTP $status",
                )
            }
            json
        } finally {
            connection.disconnect()
        }
    }
}
