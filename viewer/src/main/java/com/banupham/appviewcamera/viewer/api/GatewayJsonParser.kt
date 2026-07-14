package com.banupham.appviewcamera.viewer.api

import org.json.JSONArray
import org.json.JSONObject

object GatewayJsonParser {
    fun cameras(payload: String): List<CameraSummary> {
        val array = JSONArray(payload)
        return buildList {
            repeat(array.length()) { index ->
                val item = array.getJSONObject(index)
                val id = item.getString("id")
                add(
                    CameraSummary(
                        id = id,
                        name = item.optString("name").ifBlank { id },
                        host = item.optString("host"),
                        port = item.optInt("port", 554),
                        username = item.optString("username"),
                        mainPath = item.optString("main_path"),
                        subPath = item.optString("sub_path"),
                        relayPath = item.optString("relay_path").ifBlank { id },
                        enabled = item.optBoolean("enabled", true),
                        recordingEnabled = item.optBoolean("record_enabled", false),
                        motionEnabled = item.optBoolean("motion_enabled", false)
                    )
                )
            }
        }
    }

    fun candidates(payload: String): List<DiscoveryCandidate> {
        val trimmed = payload.trim()
        val array = if (trimmed.startsWith("[")) JSONArray(trimmed) else JSONObject(trimmed).getJSONArray("candidates")
        return buildList {
            repeat(array.length()) { index ->
                val item = array.getJSONObject(index)
                add(
                    DiscoveryCandidate(
                        host = item.getString("host"),
                        port = item.getInt("port"),
                        source = item.optString("source", "unknown")
                    )
                )
            }
        }
    }

    fun status(payload: String): GatewayStatus {
        val root = JSONObject(payload)
        return GatewayStatus(
            status = root.optString("status", "UNKNOWN"),
            mediaMtxState = root.optJSONObject("mediamtx")?.optString("state", "UNKNOWN") ?: "UNKNOWN",
            cameraCount = root.optInt("camera_count", 0)
        )
    }

    fun drives(payload: String): List<GoogleDriveAccount> {
        val array = JSONArray(payload)
        return buildList {
            repeat(array.length()) { index ->
                val item = array.getJSONObject(index)
                val quota = item.optJSONObject("quota")?.let {
                    DriveQuota(
                        total = it.optionalLong("total"),
                        used = it.optionalLong("used"),
                        free = it.optionalLong("free")
                    )
                }
                add(
                    GoogleDriveAccount(
                        id = item.getString("id"),
                        displayName = item.optString("display_name").ifBlank { item.getString("id") },
                        active = item.optBoolean("active", false),
                        configured = item.optBoolean("configured", false),
                        status = item.optString("status", "NOT_CHECKED"),
                        lastError = item.optString("last_error").takeIf { value -> value.isNotBlank() && value != "null" },
                        quota = quota
                    )
                )
            }
        }
    }

    fun recordingStatus(payload: String): RecordingStatus {
        val root = JSONObject(payload)
        return RecordingStatus(
            enabled = root.optBoolean("enabled", false),
            localRetentionMinutes = root.optInt("local_retention_minutes", 60),
            clipCount = root.optInt("clip_count", 0),
            diskFreeBytes = root.optLong("disk_free_bytes", 0),
            diskTotalBytes = root.optLong("disk_total_bytes", 0)
        )
    }

    fun recordings(payload: String): List<RecordingClip> {
        val array = JSONObject(payload).getJSONArray("clips")
        return buildList {
            repeat(array.length()) { index ->
                val item = array.getJSONObject(index)
                add(
                    RecordingClip(
                        id = item.getString("id"),
                        cameraId = item.getString("camera_id"),
                        startedAtMs = item.getLong("started_at_ms"),
                        durationMs = item.optionalLong("duration_ms"),
                        sizeBytes = item.getLong("size_bytes")
                    )
                )
            }
        }
    }

    private fun JSONObject.optionalLong(name: String): Long? =
        if (has(name) && !isNull(name)) getLong(name) else null
}
