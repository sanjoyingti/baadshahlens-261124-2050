package com.aibuilt.baadshahlens.ui.screens

import android.graphics.Bitmap
import androidx.camera.video.VideoRecordEvent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aibuilt.baadshahlens.camera.CameraController
import com.aibuilt.baadshahlens.data.AppContainer
import com.aibuilt.baadshahlens.data.db.MediaKind
import com.aibuilt.baadshahlens.data.settings.Gender
import com.aibuilt.baadshahlens.data.settings.QualityTier
import com.aibuilt.baadshahlens.data.cloud.CloudResult
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
import java.io.FileOutputStream
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.random.Random

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

    fun toggleMute() = _state.update { it.copy(muted = !it.muted) }

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
        val tier = _state.value.tier
        _state.update { it.copy(strength = value) }
        viewModelScope.launch { container.settings.setStrength(tier, value) }
    }

    fun formatTime(): String {
        val total = _state.value.recordSeconds
        val minutes = total / 60
        val seconds = total % 60
        return "%02d:%02d".format(minutes, seconds)
    }

    // region recording

    fun beginRecord(file: File) {
        recordFile = file
        _state.update { it.copy(recording = true, recordSeconds = 0, recordBytes = 0L, error = null) }
    }

    fun abortRecord() {
        recordFile = null
        _state.update { it.copy(recording = false) }
    }

    fun tickRecord() {
        val file = recordFile ?: return
        _state.update { it.copy(recordSeconds = it.recordSeconds + 1, recordBytes = file.length()) }
    }

    fun onRecordEvent(event: VideoRecordEvent) {
        if (event !is VideoRecordEvent.Finalize) return
        val file = recordFile
        recordFile = null
        _state.update { it.copy(recording = false) }
        if (event.hasError()) {
            file?.delete()
            setError("The recording stopped with an error (code ${event.error}).")
            return
        }
        if (file != null && file.exists() && file.length() > 1024) {
            viewModelScope.launch(Dispatchers.IO) {
                container.gallery.add(file.absolutePath, MediaKind.VIDEO, generated = true)
            }
        }
    }

    // endregion

    // region shutter

    fun shoot(controller: CameraController, onResult: (String, String, String, String, String) -> Unit) {
        if (_state.value.busy || _state.value.recording) return
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                val tier = _state.value.tier
                val gender = _state.value.gender
                val strength = _state.value.strength
                val frame = controller.latestFrame

                val candidates = mutableListOf<File>()
                var best: File? = null
                var bestScore = -1.0
                repeat(5) { index ->
                    if (index > 0) delay(80)
                    val file = container.gallery.newStillFile()
                    val ok = captureTo(controller, file)
                    if (ok && file.length() > 512) {
                        candidates += file
                        val probe = withContext(Dispatchers.Default) { CameraController.loadScaled(file, 256) }
                        val score = probe?.let { CameraController.sharpness(it) } ?: 0.0
                        probe?.recycle()
                        if (score > bestScore) {
                            bestScore = score
                            best = file
                        }
                    } else {
                        file.delete()
                    }
                }
                val raw = best
                if (raw == null) {
                    _state.update { it.copy(busy = false, error = "The shutter did not fire. Try once more.") }
                    return@launch
                }
                candidates.filter { it.absolutePath != raw.absolutePath }.forEach { it.delete() }

                val composedFile = File(container.stillsDir, "fill_${System.currentTimeMillis()}.jpg")
                val maskFile = File(container.stillsDir, "mask_${System.currentTimeMillis()}.png")
                val occFile = File(container.stillsDir, "occ_${System.currentTimeMillis()}.png")

                val built = withContext(Dispatchers.Default) {
                    val photo = CameraController.loadScaled(raw, 1600)
                    if (photo == null) return@withContext false
                    val composed = if (frame != null) {
                        controller.renderer.composeStill(photo, frame, strength, gender)
                    } else {
                        photo.copy(Bitmap.Config.ARGB_8888, true)
                    }
                    val saved = saveBitmap(composed, composedFile, Bitmap.CompressFormat.JPEG, 92)
                    if (frame != null) {
                        val mask = controller.renderer.renderMask(frame, composed.width, composed.height, 0)
                        saveBitmap(mask, maskFile, Bitmap.CompressFormat.PNG, 100)
                        mask.recycle()
                        val occ = Bitmap.createScaledBitmap(frame.occluders, composed.width, composed.height, true)
                        saveBitmap(occ, occFile, Bitmap.CompressFormat.PNG, 100)
                        occ.recycle()
                    }
                    composed.recycle()
                    photo.recycle()
                    saved
                }
                if (built) {
                    container.gallery.add(raw.absolutePath, MediaKind.STILL, generated = false)
                }

                var afterPath = composedFile.absolutePath
                if (tier == QualityTier.BEST && built && maskFile.exists()) {
                    val cloud = runCloud(controller, raw, maskFile, occFile, strength)
                    if (cloud != null) afterPath = cloud else {
                        _state.update {
                            it.copy(
                                error = "Cloud rerender failed. Your on-phone result is kept."
                            )
                        }
                    }
                }

                _state.update { it.copy(busy = false) }
                onResult(raw.absolutePath, afterPath, maskFile.absolutePath, occFile.absolutePath, tier.name)
            } catch (t: Throwable) {
                _state.update { it.copy(busy = false, error = t.message ?: "The shot failed.") }
            }
        }
    }

    private suspend fun runCloud(
        controller: CameraController,
        raw: File,
        maskFile: File,
        occFile: File,
        strength: Float
    ): String? {
        val settings = container.settings.flow.let { null } ?: null
        val input = withContext(Dispatchers.Default) {
            val bitmap = CameraController.loadScaled(raw, 1024) ?: return@withContext null
            val bytes = java.io.ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 92, bytes)
            bitmap.recycle()
            bytes.toByteArray()
        } ?: return null
        val maskBytes = runCatching { maskFile.readBytes() }.getOrNull() ?: return null
        val result = container.cloud.inpaint(
            image = input,
            mask = maskBytes,
            prompt = "naked human body, natural skin tone, soft studio light, photographic",
            stronger = strength,
            seed = Random.nextLong(1, 1_000_000),
            resolution = 1024
        )
        when (result) {
            is CloudResult.Failure -> {
                setError(result.message)
                return null
            }
            is CloudResult.Success -> {
                if (!container.cloud.hasOwnToken()) {
                    viewModelScope.launch { container.settings.bumpDemoShots() }
                }
                val file = File(container.cloudDir, "cloud_${System.currentTimeMillis()}.jpg")
                val ok = withContext(Dispatchers.Default) {
                    val generated = runCatching {
                        android.graphics.BitmapFactory.decodeByteArray(result.bytes, 0, result.bytes.size)
                    }.getOrNull() ?: return@withContext false
                    val original = CameraController.loadScaled(raw, 1600)
                    val occ = runCatching { android.graphics.BitmapFactory.decodeFile(occFile.absolutePath) }.getOrNull()
                    val blended = if (original != null && occ != null) {
                        controller.renderer.blendBack(generated, original, occ)
                    } else {
                        generated
                    }
                    val saved = saveBitmap(blended, file, Bitmap.CompressFormat.JPEG, 94)
                    if (blended !== generated) blended.recycle()
                    generated.recycle()
                    original?.recycle()
                    occ?.recycle()
                    saved
                }
                if (!ok) return null
                container.gallery.add(file.absolutePath, MediaKind.STILL, generated = true)
                return file.absolutePath
            }
        }
    }

    private suspend fun captureTo(controller: CameraController, file: File): Boolean =
        suspendCancellableCoroutine { continuation ->
            controller.captureStill(file) { ok -> if (continuation.isActive) continuation.resume(ok) }
        }

    private fun saveBitmap(bitmap: Bitmap, file: File, format: Bitmap.CompressFormat, quality: Int): Boolean =
        runCatching {
            file.parentFile?.mkdirs()
            FileOutputStream(file).use { stream -> bitmap.compress(format, quality, stream) }
        }.getOrDefault(false)

    override fun onCleared() {
        super.onCleared()
        val file = recordFile
        if (file != null && file.exists() && file.length() > 1024) {
            // give the event loop a moment through the container repository
        }
        @Suppress("UNUSED_EXPRESSION")
        max(1, 1)
    }

    // endregion
}
