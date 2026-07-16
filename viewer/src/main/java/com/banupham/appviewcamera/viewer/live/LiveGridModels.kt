package com.banupham.appviewcamera.viewer.live

import com.banupham.appviewcamera.viewer.api.CameraSummary

enum class LiveLayout(val slots: Int, val columns: Int, val selectable: Boolean = true) {
    ONE(1, 1),
    TWO(2, 2),
    FOUR(4, 2),
    SIX(6, 3),
    NINE(9, 3),
    SIXTEEN(16, 4);

    companion object {
        fun fromSlots(slots: Int): LiveLayout = entries.firstOrNull { it.slots == slots } ?: ONE
    }
}

data class LiveGridPlan(
    val cameras: List<CameraSummary>,
    val requestedSlots: Int,
    val decoderLimited: Boolean
)

fun reconcileCameraSelection(
    availableCameraIds: List<String>,
    selectedCameraIds: List<String>,
    slots: Int
): List<String> {
    val available = availableCameraIds.distinct()
    val selected = selectedCameraIds.filter { it in available }.distinct().toMutableList()
    for (cameraId in available) {
        if (selected.size >= slots) break
        if (cameraId !in selected) selected += cameraId
    }
    return selected.take(slots)
}

fun buildLiveGridPlan(
    cameras: List<CameraSummary>,
    selectedCameraIds: List<String>,
    layout: LiveLayout,
    decoderCapacity: Int
): LiveGridPlan {
    val enabled = cameras.filter { it.enabled }
    val selection = reconcileCameraSelection(enabled.map { it.id }, selectedCameraIds, layout.slots)
    val limit = minOf(layout.slots, decoderCapacity.coerceAtLeast(1))
    return LiveGridPlan(
        cameras = selection.take(limit).mapNotNull { id -> enabled.firstOrNull { it.id == id } },
        requestedSlots = layout.slots,
        decoderLimited = selection.size > limit
    )
}

fun liveRelayPath(camera: CameraSummary, expanded: Boolean): String =
    if (expanded || camera.previewRelayPath.isBlank()) camera.relayPath else camera.previewRelayPath

object RtspRetryPolicy {
    private val delays = longArrayOf(1_000, 3_000, 5_000, 10_000)
    fun delayMillis(attempt: Int): Long = delays[attempt.coerceIn(0, delays.lastIndex)]
}
