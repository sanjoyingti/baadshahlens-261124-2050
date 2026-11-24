package com.aibuilt.baadshahlens.ui.screens

import android.graphics.Bitmap
import androidx.camera.video.VideoRecordEvent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aibuilt.baadshahlens.camera.CameraController
import com.aibuilt.baadshahlens.data.AppContainer
import com.aibuilt.baadshahlens.data.db.MediaKind
import com.aibuilt.baadshahlens.data.settings.AppSettings
import com.aibuilt.baadshahlens.data.settings.Gender
import com.aibuilt.baadshahlens.data.settings.QualityTier
import com.aibuilt.baadshahlens.vision.OnDeviceInpainter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

data class CameraUiState(
    val tier: QualityTier = QualityTier.FAST,
    val strength: Float = 0.85f,
    val gender: Gender = Gender.MALE,
    val genderManual: Boolean = false,
    val grid: Boolean = false,
    val showGuide: Boolean = true,
    val guideDismissed: Boolean = false,
    val hintShown: Boolean = false,
    val glasses: Boolean = false,
    val cleanVideo: Boolean = false,
    val muted: Boolean = false,
    val overlayOn: Boolean = true,
    val ghost: Float = 1f,
    val busy: Boolean = false,
    val recording: Boolean = false,
    val recordSeconds: Int = 0,
    val recordBytes: Long = 0L,
    val error: String? = null
)

private data class CaptureResult(
    val before: String,
    val after: String,
    val mask: String,
    val occ: String,
    val tier: String
)

class CameraViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(CameraUiState())
    val state: StateFlow<CameraUiState> = _state.asStateFlow()

    private var recordFile: File? = null
    private var lastGenderWrite = 0L

    init {
        viewModelScope.launch {
            container.settings.flow.collect { s ->
                _state.update {
                    it.copy(
                        tier = s.effectiveTier,
                        strength = s.strengthFor(s.effectiveTier),
                        gender = if (it.genderManual) it.gender else (s.genderInferred ?: s.genderDefault),
                        cleanVideo = s.cleanGlassesRecording,
                        hintShown = s.glassesHintShown,
                        showGuide = !it.guideDismissed && s.framingGuideShown < 3,
                        overlayOn = !(it.glasses && s.cleanGlassesRecording)
                    )
                }
            }
        }
        viewModelScope.launch { container.settings.bumpFramingGuide() }
    }

    fun setError(message: String) = _state.update { it.copy(error = message) }

    fun clearError() = _state.update { it.copy(error = null) }

    fun setErrorIfEmpty() = Unit

    fun dismissGuide() {
        _state.update { it.copy(showGuide = false, guideDismissed = true) }
    }

    fun toggleGrid() = _state.update { it.copy(grid = !it.grid) }

    fun setTilt(z: Float) {
        _state.update { it.copy(ghost = if (z < 2.6f) 0.12f else 1f) }
    }

    fun toggleGlasses() {
        _state.update {
            val glasses = !it.glasses
            it.copy(glasses = glasses, overlayOn = !(glasses && it.cleanVideo), ghost = 1f)
        }
        if (_state.value.glasses) viewModelScope.launch { container.settings.setGlassesHintShown() }
    }

    fun markHintShown() {
        viewModelScope.launch { container.settings.setGlassesHintShown() }
    }

    fun sharpenTap() {
        if (_state.value.tier != QualityTier.BEST) return
        _state.update { it.copy(error = null) }
    }

    fun toggleGender() {
        _state.update {
            val next = if (it.gender == Gender.MALE) Gender.FEMALE else Gender.MALE
            it.copy(gender = next, genderManual = true)
        }
        viewModelScope.launch { container.settings.setGenderInferred(_state.value.gender) }
    }

    fun inferGender(gender: Gender?) {
        if (gender == null || _state.value.genderManual) return
        if (System.currentTimeMillis() - lastGenderWrite < 4000L) return
        lastGenderWrite = System.currentTimeMillis()
        viewModelScope.launch { container.settings.setGenderInferred(gender) }
    }

    fun setTier(tier: QualityTier) {
        viewModelScope.launch { container.settings.setTier(tier) }
    }

    fun demoteToFast() {
        if (_state.value.tier == QualityTier.FAST) return
        viewModelScope.launch {
            container.settings.setTier(QualityTier.FAST)
            setError("The phone was struggling, so the fill dropped to Fast. You can pick a higher tier again.")
        }
    }

    fun setStrength(value: Float) {
        _state.update { it.copy(strength = value) }
        viewModelScope.launch { container.settings.setStrength(_state.value.tier, value) }
    }

    fun toggleMute() = _state.update { it.copy(muted = !it.muted) }

    fun beginRecord(file: File) {
        recordFile = file
        _state.update { it.copy(recording = true, recordSeconds = 0, recordBytes = 0L) }
    }

    fun abortRecord() {
        recordFile = null
        _state.update { it.copy(recording = false) }
    }

    fun tickRecord() {
        val file = recordFile ?: return
        _state.update {
            it.copy(recordSeconds = it.recordSeconds + 1, recordBytes = file.length())
        }
    }

    fun formatTime(): String {
        val s = _state.value.recordSeconds
        return "%02d:%02d".format(s / 60, s % 60)
    }

    fun onRecordEvent(event: VideoRecordEvent) {
        if (event !is VideoRecordEvent.Finalize) return
        val file = recordFile
        val failed = event.hasError() && file?.length() ?: 0L < 2000L
        _state.update { it.copy(recording = false) }
        recordFile = null
        if (failed || file == null) {
            setError("The recording could not be saved. Free some space and try again.")
            file?.delete()
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            container.gallery.add(file.absolutePath, MediaKind.VIDEO, true)
        }
    }

    fun shoot(
        controller: CameraController,
        onDone: (String, String, String, String, String) -> Unit
    ) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            val result = runCatching { capture(controller) }.getOrNull()
            _state.update { it.copy(busy = false) }
            if (result == null) {
                setError("The photo could not be taken. Hold still and try again.")
                return@launch
            }
            onDone(result.before, result.after, result.mask, result.occ, result.tier)
        }
    }

    private suspend fun capture(controller: CameraController): CaptureResult? = withContext(Dispatchers.Default) {
        val frame = controller.latestFrame ?: return@withContext null
        val burstDir = File(container.context.cacheDir, "burst").also { it.mkdirs() }
        val files = mutableListOf<File>()
        repeat(5) { index ->
            val file = File(burstDir, "b_${System.currentTimeMillis()}_$index.jpg")
            val ok = suspendCancellableCoroutine { continuation ->
                controller.captureStill(file) { if (continuation.isActive) continuation.resume(it) }
            }
            if (ok) files += file
            delay(60)
        }
        if (files.isEmpty()) return@withContext null

        var best: File? = null
        var bestScore = -1.0
        for (file in files) {
            val sample = CameraController.loadScaled(file, 400)
            val score = sample?.let { CameraController.sharpness(it) } ?: -1.0
            sample?.recycle()
            if (score > bestScore) {
                bestScore = score
                best = file
            }
        }
        files.filter { it != best }.forEach { it.delete() }
        val chosen = best ?: return@withContext null

        val photo = CameraController.loadScaled(chosen, 1024) ?: return@withContext null
        val gender = _state.value.gender
        val strength = _state.value.strength

        val composed = controller.renderer.composeStill(photo, frame, strength, gender)
        val refined = OnDeviceInpainter.refine(
            composed,
            controller.renderer.renderMask(frame, composed.width, composed.height, controller.uprightRotation()),
            strength.hashCode(),
            strength
        )
        val beforeFile = container.gallery.newStillFile("before")
        val afterFile = File(container.stillsDir, "after_${System.currentTimeMillis()}.png")
        val maskFile = File(container.stillsDir, "mask_${System.currentTimeMillis()}.png")
        val occFile = File(container.stillsDir, "occ_${System.currentTimeMillis()}.png")

        beforeFile.outputStream().use { photo.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        afterFile.outputStream().use { refined.compress(Bitmap.CompressFormat.PNG, 100, it) }
        maskFile.outputStream().use {
            controller.renderer.renderMask(frame, refined.width, refined.height, controller.selfieRotation())
                .compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        occFile.outputStream().use {
            Bitmap.createScaledBitmap(frame.occluders, refined.width, refined.height, true)
                .compress(Bitmap.CompressFormat.PNG, 100, it)
        }

        container.gallery.add(beforeFile.absolutePath, MediaKind.STILL, false)
        container.gallery.add(afterFile.absolutePath, MediaKind.STILL, true)

        composed.recycle()
        if (refined !== composed) refined.recycle()
        photo.recycle()

        CaptureResult(
            before = beforeFile.absolutePath,
            after = afterFile.absolutePath,
            mask = maskFile.absolutePath,
            occ = occFile.absolutePath,
            tier = _state.value.tier.name
        )
    }

    fun savedSettings(): AppSettings = AppSettings()
}
