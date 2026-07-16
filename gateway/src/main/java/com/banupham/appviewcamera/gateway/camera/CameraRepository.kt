package com.banupham.appviewcamera.gateway.camera

import com.banupham.appviewcamera.gateway.database.CameraDao
import com.banupham.appviewcamera.gateway.database.CameraEntity
import com.banupham.appviewcamera.gateway.rtsp.CameraProbeResult
import com.banupham.appviewcamera.gateway.rtsp.RtspUrlFactory
import com.banupham.appviewcamera.gateway.security.CredentialCipher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class CameraRepository(
    private val cameraDao: CameraDao,
    private val credentialCipher: CredentialCipher
) {
    fun observeAll(): Flow<List<Camera>> = cameraDao.observeAll().map { cameras ->
        cameras.map { it.toDomain() }
    }

    suspend fun list(): List<Camera> = cameraDao.list().map { it.toDomain() }

    suspend fun getByRelayPath(relayPath: String): Camera? = cameraDao.getByRelayPath(relayPath)?.toDomain()

    suspend fun save(draft: CameraDraft): Long {
        val existing = draft.id.takeIf { it != 0L }?.let { cameraDao.get(it) }
        val encryptedPassword = when {
            draft.password.isNotEmpty() -> credentialCipher.encrypt(draft.password)
            existing != null -> existing.encryptedPassword
            else -> ""
        }
        val entity = CameraEntity(
            id = draft.id,
            name = draft.name.trim(),
            ip = draft.ip.trim(),
            port = draft.port.toInt(),
            username = draft.username.trim(),
            encryptedPassword = encryptedPassword,
            mainRtspUrl = RtspUrlFactory.withoutCredentials(draft.mainRtspUrl.trim()),
            subRtspUrl = draft.subRtspUrl.trim().takeIf { it.isNotEmpty() }
                ?.let(RtspUrlFactory::withoutCredentials).orEmpty(),
            relayPath = draft.relayPath.trim(),
            enabled = draft.enabled,
            recordEnabled = draft.recordEnabled,
            motionEnabled = draft.motionEnabled,
            audioEnabled = draft.audioEnabled,
            connectionStatus = existing?.connectionStatus ?: CameraConnectionStatus.OFFLINE.name,
            codec = existing?.codec,
            width = existing?.width,
            height = existing?.height,
            fps = existing?.fps,
            bitrate = existing?.bitrate,
            lastError = existing?.lastError,
            lastTestedAt = existing?.lastTestedAt,
            createdAt = existing?.createdAt ?: System.currentTimeMillis()
        )
        return cameraDao.upsert(entity)
    }

    suspend fun delete(id: Long): Boolean = cameraDao.delete(id) == 1

    suspend fun get(id: Long): Camera? = cameraDao.get(id)?.toDomain()

    fun decryptPassword(camera: Camera): String = credentialCipher.decrypt(camera.encryptedPassword)

    suspend fun markConnecting(id: Long, reconnecting: Boolean) {
        val current = cameraDao.get(id) ?: return
        cameraDao.updateProbeResult(
            id = id,
            status = if (reconnecting) CameraConnectionStatus.RECONNECTING.name else CameraConnectionStatus.CONNECTING.name,
            codec = current.codec,
            width = current.width,
            height = current.height,
            fps = current.fps,
            bitrate = current.bitrate,
            lastError = null,
            testedAt = current.lastTestedAt
        )
    }

    suspend fun saveProbeSuccess(id: Long, result: CameraProbeResult) {
        cameraDao.updateProbeResult(
            id = id,
            status = CameraConnectionStatus.CONNECTED.name,
            codec = result.codec,
            width = result.width,
            height = result.height,
            fps = result.fps,
            bitrate = result.bitrate,
            lastError = null,
            testedAt = System.currentTimeMillis()
        )
    }

    suspend fun saveProbeFailure(id: Long, safeError: String) {
        cameraDao.updateProbeResult(
            id = id,
            status = CameraConnectionStatus.OFFLINE.name,
            codec = null,
            width = null,
            height = null,
            fps = null,
            bitrate = null,
            lastError = safeError,
            testedAt = System.currentTimeMillis()
        )
    }
}

private fun CameraEntity.toDomain() = Camera(
    id = id,
    name = name,
    ip = ip,
    port = port,
    username = username,
    encryptedPassword = encryptedPassword,
    mainRtspUrl = mainRtspUrl,
    subRtspUrl = subRtspUrl,
    relayPath = relayPath,
    enabled = enabled,
    recordEnabled = recordEnabled,
    motionEnabled = motionEnabled,
    audioEnabled = audioEnabled,
    connectionStatus = runCatching { CameraConnectionStatus.valueOf(connectionStatus) }
        .getOrDefault(CameraConnectionStatus.OFFLINE),
    codec = codec,
    width = width,
    height = height,
    fps = fps,
    bitrate = bitrate,
    lastError = lastError,
    lastTestedAt = lastTestedAt,
    createdAt = createdAt
)
