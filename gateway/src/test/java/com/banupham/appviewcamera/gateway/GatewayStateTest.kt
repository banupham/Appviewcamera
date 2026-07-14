package com.banupham.appviewcamera.gateway

import org.junit.Assert.assertEquals
import org.junit.Test

class GatewayStateTest {
    @Test
    fun defaultStateIsNotConfiguredAndHasNoCamera() {
        val state = GatewayState()
        assertEquals(GatewayServiceState.NOT_CONFIGURED, state.serviceState)
        assertEquals(0, state.configuredCameraCount)
        assertEquals(0, state.recordingCameraCount)
    }

    @Test(expected = IllegalArgumentException::class)
    fun recordingCountCannotExceedConfiguredCount() {
        GatewayState(configuredCameraCount = 1, recordingCameraCount = 2)
    }
}
