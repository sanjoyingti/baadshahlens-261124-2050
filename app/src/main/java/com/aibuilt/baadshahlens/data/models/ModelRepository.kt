package com.aibuilt.baadshahlens.data.models

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

data class ModelSpec(
    val id: String,
    val label: String,
    val url: String,
    val fileName: String,
    val approxBytes: Long
)

enum class DownloadState { MISSING, RUNNING, READY, FAILED }

data class DownloadProgress(
    val states: Map<String, DownloadState> = emptyMap(),
    val bytes: Map<String, Long> = emptyMap(),
    val message: String? = null
) {
    fun stateOf(id: String): DownloadState = states[id] ?: DownloadState.MISSING
    fun fraction(spec: ModelSpec): Float {
        val got = bytes[spec.id] ?: 0L
        if (spec.approxBytes <= 0) return 0f
        return (got.toFloat() / spec.approxBytes).coerceIn(0f, 1f)
    }
}

class ModelRepository(context: Context) {

    private val dir = File(context.filesDir, "models").also { it.mkdirs() }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private val _progress = MutableStateFlow(DownloadProgress())
    val progress: StateFlow<DownloadProgress> = _progress

    val specs = listOf(
        ModelSpec(
            id = "pose",
            label = "Body skeleton tracker (BlazePose, 12 MB)",
            url = "https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_full/float16/1/pose_landmarker_full.task",
            fileName = "pose_landmarker_full.task",
            approxBytes = 12_000_000L
        ),
        ModelSpec(
            id = "seg",
            label = "Clothes and skin separator (Selfie Multiclass, 16 MB)",
            url = "https://storage.googleapis.com/mediapipe-models/image_segmenter/selfie_multiclass_256x256/float32/latest/selfie_multiclass_256x256.tflite",
            fileName = "selfie_multiclass_256x256.tflite",
            approxBytes = 16_000_000L
        )
    )

    fun fileFor(id: String): File? = specs.firstOrNull { it.id == id }?.let { File(dir, it.fileName) }

    fun isReady(id: String): Boolean = fileFor(id)?.let { it.exists() && it.length() > 100_000 } == true

    fun allReady(): Boolean = specs.all { isReady(it.id) }

    fun refresh() {
        _progress.value = DownloadProgress(
            states = specs.associate { it.id to if (isReady(it.id)) DownloadState.READY else DownloadState.MISSING }
        )
    }

    suspend fun download(spec: ModelSpec) = withContext(Dispatchers.IO) {
        if (isReady(spec.id)) {
            publish(spec.id, DownloadState.READY, 0L)
            return@withContext
        }
        publish(spec.id, DownloadState.RUNNING, 0L)
        val target = File(dir, spec.fileName)
        val temp = File(dir, spec.fileName + ".part")
        runCatching {
            val request = Request.Builder().url(spec.url).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("HTTP ${response.code}")
                val body = response.body ?: error("no body")
                temp.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    body.byteStream().use { input ->
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            out.write(buffer, 0, read)
                            total += read
                            publish(spec.id, DownloadState.RUNNING, total)
                        }
                    }
                }
            }
            if (temp.length() < 100_000) error("file too small")
            if (target.exists()) target.delete()
            temp.renameTo(target)
            publish(spec.id, DownloadState.READY, target.length())
        }.onFailure {
            temp.delete()
            _progress.value = _progress.value.copy(
                states = _progress.value.states + (spec.id to DownloadState.FAILED),
                message = "Could not fetch ${spec.label}. Check your connection."
            )
        }
    }

    suspend fun downloadAll() {
        specs.forEach { download(it) }
    }

    private fun publish(id: String, state: DownloadState, bytes: Long) {
        _progress.value = _progress.value.copy(
            states = _progress.value.states + (id to state),
            bytes = _progress.value.bytes + (id to bytes)
        )
    }
}
