package com.moto.gps

import com.moto.gps.ble.MotoBleUuids
import com.moto.gps.protocol.NativeBleEvent
import org.junit.Assert.assertEquals
import org.junit.Test
import org.json.JSONObject

class ProtocolPermissionPolicyTest {
    @Test
    fun usesTheExistingOnePointEightFiveBNavigationService() {
        assertEquals(
            "7e57a000-b50c-4b6a-9c57-40a54e8e1000",
            MotoBleUuids.service.toString(),
        )
        assertEquals(
            "7e57a001-b50c-4b6a-9c57-40a54e8e1000",
            MotoBleUuids.phoneToDevice.toString(),
        )
        assertEquals(
            "7e57a002-b50c-4b6a-9c57-40a54e8e1000",
            MotoBleUuids.deviceToPhone.toString(),
        )
    }

    @Test
    fun keepsAckMetadataForDeviceMessages() {
        val json = "{\"kind\":\"complete\",\"type\":33," +
            "\"sequence\":17,\"ackRequested\":true}"
        val jsonObject = JSONObject(json)
        assertEquals("complete", jsonObject.optString("kind"))
        assertEquals(33, jsonObject.optInt("type", -1))
        val event = NativeBleEvent.fromJson(json)
        assertEquals("DeviceMessage", event::class.simpleName)
        when (event) {
            is NativeBleEvent.DeviceMessage -> {
                assertEquals(17, event.sequence)
                assertEquals(true, event.ackRequested)
            }
            else -> error("Unexpected protocol event: $event")
        }
    }
}
