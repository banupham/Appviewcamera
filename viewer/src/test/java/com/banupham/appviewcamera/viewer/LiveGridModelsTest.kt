package com.banupham.appviewcamera.viewer

import com.banupham.appviewcamera.viewer.api.CameraSummary
import com.banupham.appviewcamera.viewer.live.LiveLayout
import com.banupham.appviewcamera.viewer.live.RtspRetryPolicy
import com.banupham.appviewcamera.viewer.live.buildLiveGridPlan
import com.banupham.appviewcamera.viewer.live.liveRelayPath
import com.banupham.appviewcamera.viewer.live.reconcileCameraSelection
import com.banupham.appviewcamera.viewer.live.updateSelectedCameras
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveGridModelsTest {
    private val cameras = (1..4).map { index -> camera("camera0$index") }

    @Test
    fun `one camera layout preserves existing single camera view`() {
        val plan = buildLiveGridPlan(cameras, listOf("camera02"), LiveLayout.ONE, 4)

        assertEquals(listOf("camera02"), plan.cameras.map { it.id })
        assertFalse(plan.decoderLimited)
    }

    @Test
    fun `two cameras have stable independent player keys`() {
        val first = buildLiveGridPlan(cameras, listOf("camera01", "camera02"), LiveLayout.TWO, 4)
        val second = buildLiveGridPlan(cameras, first.cameras.map { it.id }, LiveLayout.TWO, 4)

        assertEquals(listOf("camera01", "camera02"), first.cameras.map { it.id })
        assertEquals(first.cameras.map { it.id }, second.cameras.map { it.id })
        assertEquals(1_000L, RtspRetryPolicy.delayMillis(0))
        assertEquals(3_000L, RtspRetryPolicy.delayMillis(1))
    }

    @Test
    fun `decoder limit reduces streams without duplicating players`() {
        val plan = buildLiveGridPlan(cameras, cameras.map { it.id }, LiveLayout.FOUR, 2)

        assertEquals(listOf("camera01", "camera02"), plan.cameras.map { it.id })
        assertEquals(plan.cameras.size, plan.cameras.map { it.id }.distinct().size)
        assertTrue(plan.decoderLimited)
    }

    @Test
    fun `layout changes retain cameras and release removed player keys`() {
        val four = buildLiveGridPlan(cameras, cameras.map { it.id }, LiveLayout.FOUR, 4)
        val one = buildLiveGridPlan(cameras, four.cameras.map { it.id }, LiveLayout.ONE, 4)

        assertEquals(listOf("camera01"), one.cameras.map { it.id })
        assertEquals(setOf("camera02", "camera03", "camera04"),
            four.cameras.map { it.id }.toSet() - one.cameras.map { it.id }.toSet())
    }

    @Test
    fun `grid uses substream and expanded camera uses main stream`() {
        val camera = camera("camera01")

        assertEquals("camera01_sub", liveRelayPath(camera, expanded = false))
        assertEquals("camera01", liveRelayPath(camera, expanded = true))
    }

    @Test
    fun `selection persists valid camera ids across recreation`() {
        val stored = updateSelectedCameras(listOf("camera01"), "camera02", 2)

        assertEquals(listOf("camera01", "camera02"), stored)
        assertEquals(stored, reconcileCameraSelection(cameras.map { it.id }, stored, 2))
    }

    private fun camera(id: String) = CameraSummary(
        id = id,
        name = id,
        host = "192.0.2.${id.takeLast(1)}",
        port = 554,
        username = "admin",
        mainPath = "main",
        subPath = "sub",
        relayPath = id,
        enabled = true,
        recordingEnabled = true,
        motionEnabled = false
    )
}
