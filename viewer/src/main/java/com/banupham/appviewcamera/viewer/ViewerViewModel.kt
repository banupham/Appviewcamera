package com.banupham.appviewcamera.viewer

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.banupham.appviewcamera.viewer.api.CameraSummary
import com.banupham.appviewcamera.viewer.api.GatewayStatus
import com.banupham.appviewcamera.viewer.api.DiscoveryCandidate
import com.banupham.appviewcamera.viewer.api.CameraMutation
import com.banupham.appviewcamera.viewer.api.HttpGatewayApi
import com.banupham.appviewcamera.viewer.api.GoogleDriveAccount
import com.banupham.appviewcamera.viewer.api.GoogleDriveMutation
import com.banupham.appviewcamera.viewer.api.RecordingStatus
import com.banupham.appviewcamera.viewer.api.StorageSummary
import com.banupham.appviewcamera.viewer.api.PlaybackDay
import com.banupham.appviewcamera.viewer.api.PlaybackItem
import com.banupham.appviewcamera.viewer.security.AndroidKeystoreCredentialCipher
import com.banupham.appviewcamera.viewer.settings.GatewayConfig
import com.banupham.appviewcamera.viewer.settings.GatewayCollection
import com.banupham.appviewcamera.viewer.settings.GatewayConfigStore
import com.banupham.appviewcamera.viewer.settings.PairingUriParser
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.Locale
import java.text.SimpleDateFormat
import java.io.IOException

data class ViewerUiState(
    val config: GatewayConfig = GatewayConfig(),
    val gateways: List<GatewayConfig> = emptyList(),
    val currentGatewayId: String? = null,
    val pairingDraft: GatewayConfig? = null,
    val pairingConflict: GatewayConfig? = null,
    val gatewayStatus: GatewayStatus? = null,
    val cameras: List<CameraSummary> = emptyList(),
    val candidates: List<DiscoveryCandidate> = emptyList(),
    val drives: List<GoogleDriveAccount> = emptyList(),
    val storageSummary: StorageSummary? = null,
    val recordingStatus: RecordingStatus? = null,
    val playbackDays: List<PlaybackDay> = emptyList(),
    val playbackItems: List<PlaybackItem> = emptyList(),
    val selectedPlaybackItemId: String? = null,
    val playbackDayStartMs: Long = startOfToday(),
    val selectedCameraId: String? = null,
    val loading: Boolean = false,
    val gatewayConnectionError: String? = null,
    val message: String? = null
) {
    val selectedCamera: CameraSummary?
        get() = cameras.firstOrNull { it.id == selectedCameraId }
}

class ViewerViewModel(application: Application) : AndroidViewModel(application) {
    private val configStore = GatewayConfigStore(application, AndroidKeystoreCredentialCipher())
    private val _state = MutableStateFlow(
        runCatching {
            val collection = configStore.loadCollection()
            ViewerUiState(
                config = collection.current ?: GatewayConfig(),
                gateways = collection.gateways,
                currentGatewayId = collection.current?.id
            )
        }
            .getOrElse {
                ViewerUiState(message = "Không đọc được cài đặt cũ; hãy cấu hình lại Gateway")
            }
    )
    val state: StateFlow<ViewerUiState> = _state.asStateFlow()
    private var refreshJob: Job? = null
    private var actionJob: Job? = null

    init {
        if (_state.value.config.validate().isSuccess) refresh()
    }

    fun saveConfig(name: String, host: String, apiPort: String, rtspPort: String, token: String) {
        val base = _state.value.pairingDraft ?: _state.value.config
        val config = GatewayConfig(
            host = host,
            apiPort = apiPort.toIntOrNull() ?: -1,
            rtspPort = rtspPort.toIntOrNull() ?: -1,
            apiToken = token,
            id = base.id,
            name = name,
            lastSeen = base.lastSeen,
            status = base.status,
            cameraCount = base.cameraCount,
            isDefault = base.isDefault
        )
        val validated = config.validate().getOrElse { error ->
            _state.update { it.copy(message = error.message ?: "Cấu hình không hợp lệ") }
            return
        }
        val collection = runCatching { configStore.save(validated) }
            .onFailure { error ->
                _state.update { it.copy(message = "Không lưu được API token: ${error.message}") }
                return
            }.getOrThrow()
        activateCollection(collection, "Đã lưu ${validated.name}", shouldRefresh = true)
    }

    fun applyPairingUri(value: String) {
        val config = PairingUriParser.parse(value).getOrElse { error ->
            _state.update { it.copy(message = error.message ?: "Chuỗi ghép nối không hợp lệ") }
            return
        }
        val duplicate = _state.value.gateways.firstOrNull { it.id == config.id }
        _state.update { current ->
            if (duplicate != null) {
                current.copy(
                    pairingConflict = config,
                    message = "Gateway ID ${config.id} đã tồn tại; hãy xác nhận cập nhật"
                )
            } else {
                current.copy(
                    pairingDraft = config,
                    pairingConflict = null,
                    message = "Đã nhận Gateway mới từ QR; bấm Lưu và kết nối"
                )
            }
        }
    }

    fun confirmPairingUpdate() {
        val incoming = _state.value.pairingConflict ?: return
        val existing = _state.value.gateways.firstOrNull { it.id == incoming.id } ?: return
        val updated = incoming.copy(
            name = existing.name,
            lastSeen = existing.lastSeen,
            status = existing.status,
            cameraCount = existing.cameraCount,
            isDefault = existing.isDefault
        )
        val collection = runCatching { configStore.save(updated) }.getOrElse { error ->
            _state.update { it.copy(message = "Không cập nhật được Gateway: ${error.message}") }
            return
        }
        activateCollection(collection, "Đã cập nhật ${updated.name}", shouldRefresh = true)
    }

    fun dismissPairingConflict() {
        _state.update { it.copy(pairingConflict = null, message = null) }
    }

    fun beginAddGateway() {
        _state.update {
            it.copy(
                pairingDraft = GatewayConfig(name = "Gateway mới"),
                pairingConflict = null,
                message = "Nhập cấu hình hoặc quét QR Gateway mới"
            )
        }
    }

    fun beginEditGateway(gatewayId: String) {
        val gateway = _state.value.gateways.firstOrNull { it.id == gatewayId } ?: return
        _state.update {
            it.copy(
                pairingDraft = gateway,
                pairingConflict = null,
                message = "Đang sửa ${gateway.name}"
            )
        }
    }

    fun cancelGatewayDraft() {
        _state.update { it.copy(pairingDraft = null, pairingConflict = null, message = null) }
    }

    fun selectGateway(gatewayId: String) {
        val collection = runCatching { configStore.select(gatewayId) }.getOrElse { error ->
            _state.update { it.copy(message = error.message) }
            return
        }
        activateCollection(collection, null, shouldRefresh = true)
    }

    fun renameGateway(gatewayId: String, name: String) {
        val collection = configStore.rename(gatewayId, name)
        val selected = collection.current
        _state.update {
            it.copy(
                gateways = collection.gateways,
                config = selected ?: GatewayConfig(),
                message = "Đã đổi tên Gateway"
            )
        }
    }

    fun deleteGateway(gatewayId: String) {
        val collection = configStore.delete(gatewayId)
        val removedCurrent = _state.value.currentGatewayId == gatewayId
        if (removedCurrent) {
            activateCollection(collection, "Chỉ đã xóa cấu hình trên Viewer", shouldRefresh = collection.current != null)
        } else {
            _state.update { it.copy(gateways = collection.gateways, message = "Chỉ đã xóa cấu hình trên Viewer") }
        }
    }

    fun refresh() {
        val config = _state.value.config.validate().getOrElse { error ->
            _state.update { it.copy(message = error.message) }
            return
        }
        val gatewayId = _state.value.currentGatewayId ?: config.id
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            _state.update { it.copy(loading = true, message = null) }
            runCatching {
                val api = HttpGatewayApi(config)
                RefreshPayload(
                    gatewayStatus = api.status(),
                    cameras = api.cameras(),
                    drives = api.drives(),
                    recordingStatus = api.recordingStatus(),
                    storageSummary = api.storageSummary()
                )
            }.onSuccess { payload ->
                if (_state.value.currentGatewayId != gatewayId) return@onSuccess
                val gatewayStatus = payload.gatewayStatus
                val cameras = payload.cameras
                val collection = configStore.updateStatus(
                    gatewayId,
                    status = "ONLINE",
                    lastSeen = System.currentTimeMillis(),
                    cameraCount = gatewayStatus.cameraCount
                )
                _state.update { current ->
                    val selected = current.selectedCameraId?.takeIf { id -> cameras.any { it.id == id } }
                        ?: cameras.firstOrNull { it.enabled }?.id
                    current.copy(
                        gatewayStatus = gatewayStatus,
                        gateways = collection.gateways,
                        config = collection.current ?: current.config,
                        cameras = cameras,
                        drives = payload.drives,
                        storageSummary = payload.storageSummary,
                        recordingStatus = payload.recordingStatus,
                        selectedCameraId = selected,
                        loading = false,
                        gatewayConnectionError = null,
                        message = null
                    )
                }
            }.onFailure { error ->
                if (error is CancellationException) return@onFailure
                val collection = configStore.updateStatus(gatewayId, status = "OFFLINE")
                if (_state.value.currentGatewayId != gatewayId) return@onFailure
                _state.update {
                    val networkFailure = error is IOException
                    val text = if (networkFailure) {
                        "Không kết nối được Gateway: ${error.message ?: "không rõ lỗi"}"
                    } else {
                        error.message ?: "Gateway từ chối yêu cầu"
                    }
                    it.copy(
                        loading = false,
                        gateways = collection.gateways,
                        config = collection.current ?: it.config,
                        gatewayStatus = null,
                        gatewayConnectionError = text.takeIf { networkFailure },
                        message = text
                    )
                }
            }
        }
    }

    fun selectCamera(cameraId: String) {
        _state.update { current ->
            if (current.cameras.any { it.id == cameraId }) current.copy(selectedCameraId = cameraId) else current
        }
    }

    fun scanCameras() = launchApiAction("Đang quét camera trong LAN…") { api ->
        val candidates = api.scanCameras()
        _state.update { it.copy(candidates = candidates, loading = false, message = "Tìm thấy ${candidates.map { item -> item.host }.distinct().size} thiết bị") }
    }

    fun saveCamera(camera: CameraMutation) = launchApiAction("Đang lưu camera…") { api ->
        api.saveCamera(camera)
        val cameras = api.cameras()
        _state.update { it.copy(cameras = cameras, selectedCameraId = camera.id, loading = false, message = "Đã lưu ${camera.name}") }
    }

    fun deleteCamera(cameraId: String) = launchApiAction("Đang xóa camera…") { api ->
        api.deleteCamera(cameraId)
        val cameras = api.cameras()
        _state.update { current ->
            current.copy(
                cameras = cameras,
                selectedCameraId = current.selectedCameraId?.takeIf { id -> cameras.any { it.id == id } }
                    ?: cameras.firstOrNull()?.id,
                loading = false,
                message = "Đã xóa camera"
            )
        }
    }

    fun addDrive(drive: GoogleDriveMutation) = launchApiAction("Đang lưu tài khoản Drive…") { api ->
        api.addDrive(drive)
        val drives = api.drives()
        _state.update { it.copy(drives = drives, loading = false, message = "Đã lưu ${drive.displayName}") }
    }

    fun authorizeDrive(remoteId: String, displayName: String) =
        launchApiAction("Đang chuẩn bị đăng nhập Google…") { api ->
            DriveOAuthCoordinator(getApplication(), api).authorize(remoteId, displayName)
            val account = api.refreshDrive(remoteId)
            _state.update {
                it.copy(
                    drives = api.drives(),
                    storageSummary = api.storageSummary(),
                    loading = false,
                    message = if (account.status == "ONLINE") {
                        "Đã kết nối Google Drive"
                    } else {
                        account.lastError ?: "Đã lưu tài khoản nhưng chưa kiểm tra được Internet"
                    }
                )
            }
        }

    fun refreshDrive(driveId: String) = launchApiAction("Đang kiểm tra Google Drive…") { api ->
        api.refreshDrive(driveId)
        val drives = api.drives()
        val account = drives.firstOrNull { it.id == driveId }
        val message = if (account?.status == "ONLINE") "Google Drive hoạt động" else account?.lastError ?: "Không kết nối được Drive"
        _state.update { it.copy(drives = drives, loading = false, message = message) }
    }

    fun deleteDrive(driveId: String) = launchApiAction("Đang xóa tài khoản Drive…") { api ->
        api.deleteDrive(driveId)
        _state.update { it.copy(drives = api.drives(), loading = false, message = "Đã xóa tài khoản Drive") }
    }

    fun loadRecordings(cameraId: String? = _state.value.selectedCameraId) =
        launchApiAction("Đang đọc danh sách clip…") { api ->
            if (cameraId == null) {
                _state.update {
                    it.copy(
                        playbackDays = emptyList(),
                        playbackItems = emptyList(),
                        selectedPlaybackItemId = null,
                        loading = false,
                        message = "Chưa có camera để xem lại"
                    )
                }
                return@launchApiAction
            }
            val dayStart = _state.value.playbackDayStartMs
            val days = api.playbackDays(cameraId)
            val clips = api.playbackTimeline(cameraId, dayStart, dayStart + DAY_MS)
            _state.update { current ->
                current.copy(
                    playbackDays = days,
                    playbackItems = clips,
                    selectedPlaybackItemId = current.selectedPlaybackItemId?.takeIf { id -> clips.any { it.id == id } }
                        ?: clips.firstOrNull()?.id,
                    loading = false,
                    message = if (clips.isEmpty()) "Chưa có clip hoàn tất" else null
                )
            }
        }

    fun changePlaybackDay(deltaDays: Int) {
        _state.update { current ->
            current.copy(
                playbackDayStartMs = current.playbackDayStartMs + deltaDays * DAY_MS,
                selectedPlaybackItemId = null
            )
        }
        loadRecordings()
    }

    fun selectPlaybackDay(day: String) {
        val parsed = runCatching {
            SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { isLenient = false }.parse(day)?.time
                ?: error("Ngày không hợp lệ")
        }.getOrElse { error ->
            _state.update { it.copy(message = error.message ?: "Ngày không hợp lệ") }
            return
        }
        _state.update {
            it.copy(playbackDayStartMs = parsed, selectedPlaybackItemId = null)
        }
        loadRecordings()
    }

    fun setRecordingEnabled(enabled: Boolean, retentionMinutes: Int = 60) =
        launchApiAction(if (enabled) "Đang bật ghi hình…" else "Đang tắt ghi hình…") { api ->
            val status = api.updateRecording(enabled, retentionMinutes)
            _state.update {
                it.copy(
                    recordingStatus = status,
                    loading = false,
                    message = if (enabled) "Đã bật ghi hình; clip đầu tiên có sau khoảng 60 giây" else "Đã tắt ghi hình"
                )
            }
        }

    fun activateDrive(driveId: String) = launchApiAction("Đang chuyển Google Drive…") { api ->
        api.activateDrive(driveId)
        _state.update {
            it.copy(drives = api.drives(), loading = false, message = "Đã chọn tài khoản Drive")
        }
    }

    fun selectRecording(recordingId: String) {
        _state.update { current ->
            if (current.playbackItems.any { it.id == recordingId }) {
                current.copy(selectedPlaybackItemId = recordingId)
            } else current
        }
    }

    fun protectRecording(recordingId: String, protected: Boolean) =
        launchApiAction(if (protected) "Đang bảo vệ clip…" else "Đang bỏ bảo vệ clip…") { api ->
            api.protectRecording(recordingId, protected)
            val current = _state.value
            val cameraId = current.selectedCameraId ?: return@launchApiAction
            val clips = api.playbackTimeline(
                cameraId,
                current.playbackDayStartMs,
                current.playbackDayStartMs + DAY_MS
            )
            _state.update {
                it.copy(
                    playbackItems = clips,
                    loading = false,
                    message = if (protected) "Clip đã được bảo vệ" else "Đã bỏ bảo vệ clip"
                )
            }
        }

    fun checkGateway(gatewayId: String) {
        val gateway = _state.value.gateways.firstOrNull { it.id == gatewayId } ?: return
        viewModelScope.launch {
            runCatching { HttpGatewayApi(gateway).status() }
                .onSuccess { status ->
                    val collection = configStore.updateStatus(
                        gatewayId,
                        status = "ONLINE",
                        lastSeen = System.currentTimeMillis(),
                        cameraCount = status.cameraCount
                    )
                    _state.update { current ->
                        current.copy(
                            gateways = collection.gateways,
                            config = collection.current ?: current.config,
                            message = "${gateway.name} đang online"
                        )
                    }
                }
                .onFailure {
                    val collection = configStore.updateStatus(gatewayId, status = "OFFLINE")
                    _state.update { current ->
                        current.copy(
                            gateways = collection.gateways,
                            config = collection.current ?: current.config,
                            message = "Không kết nối được ${gateway.name}"
                        )
                    }
                }
        }
    }

    private fun activateCollection(
        collection: GatewayCollection,
        message: String?,
        shouldRefresh: Boolean
    ) {
        refreshJob?.cancel()
        actionJob?.cancel()
        val selected = collection.current ?: GatewayConfig()
        _state.update { it.activateGateway(collection, message) }
        if (shouldRefresh && selected.validate().isSuccess) refresh()
    }

    private fun launchApiAction(progress: String, block: suspend (HttpGatewayApi) -> Unit) {
        val config = _state.value.config.validate().getOrElse { error ->
            _state.update { it.copy(message = error.message) }
            return
        }
        val gatewayId = _state.value.currentGatewayId
        actionJob?.cancel()
        actionJob = viewModelScope.launch {
            _state.update { it.copy(loading = true, message = progress) }
            runCatching { block(HttpGatewayApi(config)) }
                .onSuccess {
                    if (_state.value.currentGatewayId == gatewayId) {
                        _state.update { it.copy(gatewayConnectionError = null) }
                    }
                }
                .onFailure { error ->
                    if (error is CancellationException) return@onFailure
                    if (_state.value.currentGatewayId != gatewayId) return@onFailure
                    _state.update {
                        val text = error.message ?: "Thao tác thất bại"
                        it.copy(
                            loading = false,
                            gatewayConnectionError = if (error is IOException) {
                                "Không kết nối được Gateway: $text"
                            } else {
                                it.gatewayConnectionError
                            },
                            message = text
                        )
                    }
                }
        }
    }

    fun clearMessage() {
        _state.update { it.copy(message = null) }
    }
}

private const val DAY_MS = 24L * 60 * 60 * 1000

private fun startOfToday(): Long = Calendar.getInstance().apply {
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
}.timeInMillis

private data class RefreshPayload(
    val gatewayStatus: GatewayStatus,
    val cameras: List<CameraSummary>,
    val drives: List<GoogleDriveAccount>,
    val recordingStatus: RecordingStatus,
    val storageSummary: StorageSummary
)

internal fun ViewerUiState.activateGateway(
    collection: GatewayCollection,
    message: String? = null
): ViewerUiState = copy(
    config = collection.current ?: GatewayConfig(),
    gateways = collection.gateways,
    currentGatewayId = collection.current?.id,
    pairingDraft = null,
    pairingConflict = null,
    gatewayStatus = null,
    cameras = emptyList(),
    candidates = emptyList(),
    drives = emptyList(),
    storageSummary = null,
    recordingStatus = null,
    playbackDays = emptyList(),
    playbackItems = emptyList(),
    selectedPlaybackItemId = null,
    selectedCameraId = null,
    loading = false,
    gatewayConnectionError = null,
    message = message
)
