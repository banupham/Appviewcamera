package com.banupham.appviewcamera.viewer.live

import android.content.Context

class LiveViewPreferences(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun loadLayout(): LiveLayout = LiveLayout.fromSlots(preferences.getInt(KEY_LAYOUT_SLOTS, 1))

    fun loadCameraIds(): List<String> = preferences.getString(KEY_CAMERA_IDS, "")
        .orEmpty()
        .split(',')
        .filter(String::isNotBlank)

    fun save(layout: LiveLayout, cameraIds: List<String>) {
        preferences.edit()
            .putInt(KEY_LAYOUT_SLOTS, layout.slots)
            .putString(KEY_CAMERA_IDS, cameraIds.distinct().joinToString(","))
            .apply()
    }

    private companion object {
        const val PREFERENCES = "live_view"
        const val KEY_LAYOUT_SLOTS = "layout_slots"
        const val KEY_CAMERA_IDS = "camera_ids"
    }
}
