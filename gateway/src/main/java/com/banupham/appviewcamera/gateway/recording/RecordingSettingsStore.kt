package com.banupham.appviewcamera.gateway.recording

import android.content.Context

data class RecordingSettings(
    val enabled: Boolean,
    val localRetentionMinutes: Int,
    val storagePaused: Boolean
) {
    val effectiveEnabled: Boolean get() = enabled && !storagePaused
}

class RecordingSettingsStore(context: Context) {
    private val preferences = context.getSharedPreferences("gateway_recording", Context.MODE_PRIVATE)

    fun get(): RecordingSettings = RecordingSettings(
        enabled = preferences.getBoolean("enabled", true),
        localRetentionMinutes = preferences.getInt("local_retention_minutes", 60).coerceIn(5, 7 * 24 * 60),
        storagePaused = preferences.getBoolean("storage_paused", false)
    )

    fun update(enabled: Boolean, localRetentionMinutes: Int?): RecordingSettings {
        val current = get()
        val updated = RecordingSettings(
            enabled = enabled,
            localRetentionMinutes = (localRetentionMinutes ?: current.localRetentionMinutes).coerceIn(5, 7 * 24 * 60),
            storagePaused = if (enabled) false else current.storagePaused
        )
        preferences.edit()
            .putBoolean("enabled", updated.enabled)
            .putInt("local_retention_minutes", updated.localRetentionMinutes)
            .putBoolean("storage_paused", updated.storagePaused)
            .apply()
        return updated
    }

    fun pauseForStorage(): RecordingSettings {
        preferences.edit().putBoolean("storage_paused", true).apply()
        return get()
    }
}
