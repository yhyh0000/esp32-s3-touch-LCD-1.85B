package com.moto.gps.navigation

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class LocationFix(
    val longitudeDeg: Double,
    val latitudeDeg: Double,
    val accuracyM: Float,
    val speedMps: Float,
    val bearingDeg: Float,
    val timestampMs: Long,
)

class LocationProvider(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(LocationManager::class.java)
    private val _fix = MutableStateFlow<LocationFix?>(null)
    val fix: StateFlow<LocationFix?> = _fix.asStateFlow()
    private var started = false

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            val accuracy = if (location.hasAccuracy()) location.accuracy else Float.MAX_VALUE
            _fix.value = LocationFix(
                longitudeDeg = location.longitude,
                latitudeDeg = location.latitude,
                accuracyM = accuracy,
                speedMps = if (location.hasSpeed()) location.speed else 0f,
                bearingDeg = if (location.hasBearing()) location.bearing else 0f,
                timestampMs = location.time,
            )
        }
    }

    @SuppressLint("MissingPermission")
    fun start(): Result<Unit> {
        if (!hasPermission()) return Result.failure(IllegalStateException("缺少精确定位权限"))
        if (started) return Result.success(Unit)
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { runCatching { manager?.isProviderEnabled(it) == true }.getOrDefault(false) }
        if (providers.isEmpty()) return Result.failure(IllegalStateException("手机定位服务未开启"))
        providers.forEach { provider ->
            manager?.requestLocationUpdates(provider, 1_000L, 1f, listener, Looper.getMainLooper())
            manager?.getLastKnownLocation(provider)?.let(listener::onLocationChanged)
        }
        started = true
        return Result.success(Unit)
    }

    fun stop() {
        if (!started) return
        manager?.removeUpdates(listener)
        started = false
    }

    override fun close() = stop()

    private fun hasPermission(): Boolean = ContextCompat.checkSelfPermission(
        appContext,
        Manifest.permission.ACCESS_FINE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED
}
