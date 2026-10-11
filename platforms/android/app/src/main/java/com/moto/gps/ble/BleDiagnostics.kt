package com.moto.gps.ble

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * In-app BLE diagnostics mirrored to Logcat.
 *
 * The ring buffer is intentionally bounded so a stalled connection cannot
 * grow the Android process indefinitely. The UI can copy this trace after a
 * connect/disconnect attempt without requiring a USB cable.
 */
object BleDiagnostics {
    private const val MAX_ENTRIES = 500
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
        .withZone(ZoneId.systemDefault())
    private val _entries = MutableStateFlow<List<String>>(emptyList())

    val entries: StateFlow<List<String>> = _entries.asStateFlow()

    fun d(tag: String, message: String) = append("D", tag, message) {
        Log.d(tag, message)
    }

    fun i(tag: String, message: String) = append("I", tag, message) {
        Log.i(tag, message)
    }

    fun w(tag: String, message: String) = append("W", tag, message) {
        Log.w(tag, message)
    }

    fun w(tag: String, message: String, error: Throwable) = append(
        "W",
        tag,
        "$message\n${Log.getStackTraceString(error)}",
    ) {
        Log.w(tag, message, error)
    }

    fun e(tag: String, message: String) = append("E", tag, message) {
        Log.e(tag, message)
    }

    fun e(tag: String, message: String, error: Throwable) = append(
        "E",
        tag,
        "$message\n${Log.getStackTraceString(error)}",
    ) {
        Log.e(tag, message, error)
    }

    fun clear() {
        _entries.value = emptyList()
        Log.i("MotoBleDiagnostics", "in-app diagnostics cleared")
    }

    fun copyText(): String = _entries.value.joinToString(separator = "\n")

    private fun append(
        level: String,
        tag: String,
        message: String,
        writeToLogcat: () -> Unit,
    ) {
        writeToLogcat()
        val now = System.currentTimeMillis()
        val elapsed = android.os.SystemClock.elapsedRealtime()
        val line = "${timeFormatter.format(Instant.ofEpochMilli(now))} +${elapsed}ms " +
            "$level/$tag $message"
        _entries.value = (_entries.value + line).takeLast(MAX_ENTRIES)
    }
}
