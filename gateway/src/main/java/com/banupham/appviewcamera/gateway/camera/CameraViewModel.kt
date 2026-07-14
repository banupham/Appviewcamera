package com.banupham.appviewcamera.gateway.camera

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.banupham.appviewcamera.gateway.GatewayContainer
import com.banupham.appviewcamera.gateway.rtsp.CameraProbe
import com.banupham.appviewcamera.gateway.rtsp.CredentialRedactor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

class CameraViewModel(
    private val repository: CameraRepository,
    private val cameraProbe: CameraProbe
) : ViewModel() {
    val cameras: StateFlow<List<Camera>> = repository.observeAll().stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList()
    )

    private val _draft = MutableStateFlow<CameraDraft?>(null)
    val draft = _draft.asStateFlow()

    private val _validationErrors = MutableStateFlow<List<String>>(emptyList())
    val validationErrors = _validationErrors.asStateFlow()

    private val _testingCameraIds = MutableStateFlow<Set<Long>>(emptySet())
    val testingCameraIds = _testingCameraIds.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()

    fun beginAdd() {
        val existingPaths = cameras.value.map { it.relayPath }.toSet()
        val nextPath = generateSequence(1) { it + 1 }
            .map { "camera${it.toString().padStart(2, '0')}" }
            .first { it !in existingPaths }
        _validationErrors.value = emptyList()
        _draft.value = CameraDraft(relayPath = nextPath)
    }

    fun beginEdit(camera: Camera) {
        _validationErrors.value = emptyList()
        _draft.value = CameraDraft(
            id = camera.id,
            name = camera.name,
            ip = camera.ip,
            port = camera.port.toString(),
            username = camera.username,
            password = "",
            mainRtspUrl = camera.mainRtspUrl,
            subRtspUrl = camera.subRtspUrl,
            relayPath = camera.relayPath,
            enabled = camera.enabled,
            recordEnabled = camera.recordEnabled,
            motionEnabled = camera.motionEnabled,
            audioEnabled = camera.audioEnabled
        )
    }

    fun updateDraft(transform: (CameraDraft) -> CameraDraft) {
        _draft.value = _draft.value?.let(transform)
        _validationErrors.value = emptyList()
    }

    fun dismissEditor() {
        _draft.value = null
        _validationErrors.value = emptyList()
    }

    fun save() {
        val cameraDraft = _draft.value ?: return
        val errors = CameraValidator.validate(cameraDraft)
        if (errors.isNotEmpty()) {
            _validationErrors.value = errors
            return
        }
        viewModelScope.launch {
            runCatching { repository.save(cameraDraft) }
                .onSuccess {
                    _draft.value = null
                    _message.value = if (cameraDraft.id == 0L) "Đã thêm camera" else "Đã cập nhật camera"
                }
                .onFailure { _validationErrors.value = listOf(safeError(it, "Không thể lưu camera")) }
        }
    }

    fun delete(camera: Camera) {
        if (camera.id in _testingCameraIds.value) return
        viewModelScope.launch {
            runCatching { repository.delete(camera.id) }
                .onSuccess { deleted -> _message.value = if (deleted) "Đã xóa ${camera.name}" else "Camera không còn tồn tại" }
                .onFailure { _message.value = safeError(it, "Không thể xóa camera") }
        }
    }

    fun test(camera: Camera) {
        if (camera.id in _testingCameraIds.value) return
        _testingCameraIds.value = _testingCameraIds.value + camera.id
        viewModelScope.launch {
            try {
                repository.markConnecting(camera.id, reconnecting = false)
                val freshCamera = repository.get(camera.id) ?: error("Camera không còn tồn tại")
                val result = cameraProbe.probe(freshCamera) { _, _ ->
                    viewModelScope.launch { repository.markConnecting(camera.id, reconnecting = true) }
                }
                repository.saveProbeSuccess(camera.id, result)
                _message.value = "Kết nối RTSP thành công: ${camera.name}"
            } catch (failure: Throwable) {
                if (failure is CancellationException) throw failure
                val safeMessage = safeError(failure, "Không thể kết nối RTSP")
                repository.saveProbeFailure(camera.id, safeMessage)
                _message.value = safeMessage
            } finally {
                _testingCameraIds.value = _testingCameraIds.value - camera.id
            }
        }
    }

    fun consumeMessage() {
        _message.value = null
    }

    private fun safeError(failure: Throwable, fallback: String): String {
        val redacted = CredentialRedactor.redact(failure.message)
        return if (redacted == "Lỗi RTSP không xác định") fallback else redacted
    }

    companion object {
        fun factory(container: GatewayContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    require(modelClass.isAssignableFrom(CameraViewModel::class.java))
                    return CameraViewModel(container.cameraRepository, container.cameraProbe) as T
                }
            }
    }
}
