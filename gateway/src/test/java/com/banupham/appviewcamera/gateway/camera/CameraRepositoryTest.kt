package com.banupham.appviewcamera.gateway.camera

import com.banupham.appviewcamera.gateway.database.CameraDao
import com.banupham.appviewcamera.gateway.database.CameraEntity
import com.banupham.appviewcamera.gateway.security.CredentialCipher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class CameraRepositoryTest {
    @Test
    fun migratesEmbeddedCredentialsAndNormalizesEndpointBeforeStorage() = runTest {
        val dao = FakeCameraDao()
        val repository = CameraRepository(dao, FakeCipher())

        repository.save(
            CameraDraft(
                name = "Camera",
                ip = "192.168.1.20",
                port = "554",
                mainRtspUrl = "rtsp://admin:p%40ss@old-host:8554/Streaming/Channels/101",
                relayPath = "camera01"
            )
        )

        val saved = requireNotNull(dao.saved)
        assertEquals("admin", saved.username)
        assertEquals("enc:p@ss", saved.encryptedPassword)
        assertEquals("rtsp://192.168.1.20:554/Streaming/Channels/101", saved.mainRtspUrl)
        assertFalse(saved.mainRtspUrl.contains("admin"))
    }

    private class FakeCipher : CredentialCipher {
        override fun encrypt(plainText: String): String = "enc:$plainText"
        override fun decrypt(cipherText: String): String = cipherText.removePrefix("enc:")
    }

    private class FakeCameraDao : CameraDao {
        var saved: CameraEntity? = null

        override fun observeAll(): Flow<List<CameraEntity>> = flowOf(listOfNotNull(saved))
        override suspend fun list(): List<CameraEntity> = listOfNotNull(saved)
        override suspend fun get(id: Long): CameraEntity? = saved?.takeIf { it.id == id }
        override suspend fun getByRelayPath(relayPath: String): CameraEntity? =
            saved?.takeIf { it.relayPath == relayPath }

        override suspend fun upsert(camera: CameraEntity): Long {
            saved = camera.copy(id = camera.id.takeIf { it != 0L } ?: 1L)
            return requireNotNull(saved).id
        }

        override suspend fun delete(id: Long): Int {
            if (saved?.id != id) return 0
            saved = null
            return 1
        }

        override suspend fun updateProbeResult(
            id: Long,
            status: String,
            codec: String?,
            width: Int?,
            height: Int?,
            fps: Float?,
            bitrate: Int?,
            lastError: String?,
            testedAt: Long?
        ) = Unit
    }
}
