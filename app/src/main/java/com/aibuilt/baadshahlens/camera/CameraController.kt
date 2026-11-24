package com.aibuilt.baadshahlens.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PorterDuff
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Rational
import android.util.Size
import android.view.Display
import android.view.Surface
import androidx.camera.core.Camera
import androidx.camera.core.CameraEffect
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalCameraEffect
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.MeteringPointFactory
import androidx.camera.core.Preview
import androidx.camera.core.ResolutionSelector
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.effects.OverlayEffect
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.util.Consumer
import androidx.lifecycle.LifecycleOwner
import com.aibuilt.baadshahlens.data.settings.Gender
import com.aibuilt.baadshahlens.vision.BodyOverlayRenderer
import com.aibuilt.baadshahlens.vision.VisionPipeline
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.min

@OptIn(ExperimentalCameraEffect::class)
class CameraController(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val onFrame: (VisionPipeline.Frame) -> Unit,
    private val onRecordEvent: (VideoRecordEvent) -> Unit
) {

    private val mainExecutor = ContextCompat.getMainExecutor(context)
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private val pipeline = VisionPipeline()
    val renderer = BodyOverlayRenderer()

    @Volatile var latestFrame: VisionPipeline.Frame? = null
    @Volatile var strength: Float = 0.9f
    @Volatile var gender: Gender = Gender.MALE
    @Volatile var overlayOn: Boolean = true
    @Volatile var ghostAlpha: Float = 1f

    private var effect: OverlayEffect? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var camera: Camera? = null
    private var provider: ProcessCameraProvider? = null
    private var recording: Recording? = null
    private var bound = false

    val isRecording: Boolean get() = recording != null
    val hasPipeline: Boolean get() = pipeline.isReady

    fun prepare(poseModel: File?, segModel: File?): Boolean = pipeline.initialize(context, poseModel, segModel)

    fun pipelineGender(): Gender? = pipeline.suggestedGender()

    suspend fun bind(previewView: PreviewView, lensFacing: Int): String? {
        if (bound) return null
        if (!pipeline.isReady) return "Tracking models are not downloaded yet."
        val cameraProvider = ProcessCameraProvider.getInstance(context).awaitProvider(mainExecutor)
            ?: return "The camera could not start."
        provider = cameraProvider

        val targets = CameraEffect.PREVIEW or CameraEffect.VIDEO_CAPTURE or CameraEffect.IMAGE_CAPTURE
        val overlayEffect = OverlayEffect(targets, 2, handler, Consumer { it.printStackTrace() })
        overlayEffect.setOnDrawListener { frame ->
            val latest = latestFrame
            val canvas = frame.canvas
            if (latest != null && overlayOn) {
                runCatching { renderer.render(canvas, latest, uprightRotation(), strength, gender, ghostAlpha) }
            } else {
                canvas.drawColor(0, PorterDuff.Mode.CLEAR)
            }
            true
        }
        effect = overlayEffect

        val preview = Preview.Builder().build().also {
            it.surfaceProvider = previewView.surfaceProvider
        }
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(640, 480),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                        )
                    )
                    .build()
            )
            .build()
        analysis.setAnalyzer(analysisExecutor) { proxy ->
            val stamp = proxy.imageInfo.timestamp
            val frame = pipeline.analyze(proxy)
            if (frame != null) {
                val old = latestFrame
                latestFrame = frame
                onFrame(frame)
                old?.bitmap?.let { if (!it.isRecycled) it.recycle() }
            }
            effect?.drawFrameAsync(stamp)
        }

        imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()

        val recorder = Recorder.Builder()
            .setQualitySelector(
                QualitySelector.fromOrderedList(
                    listOf(Quality.FHD, Quality.HD, Quality.SD),
                    FallbackStrategy.lowerQualityOrHigherThan(Quality.HD)
                )
            )
            .build()
        val video = VideoCapture.withOutput(recorder)
        videoCapture = video

        val rotation = previewView.display?.rotation ?: Surface.ROTATION_0
        val group = UseCaseGroup.Builder()
            .addUseCase(preview)
            .addUseCase(analysis)
            .addUseCase(imageCapture!!)
            .addUseCase(video)
            .addEffect(overlayEffect)
            .setViewPort(ViewPort.Builder(Rational(3, 4), rotation).build())
            .build()

        return runCatching {
            cameraProvider.unbindAll()
            camera = cameraProvider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.Builder().requireLensFacing(lensFacing).build(),
                group
            )
            bound = true
            null
        }.getOrElse {
            "The camera could not start: ${it.message ?: "unknown reason"}"
        }
    }

    fun unbind() {
        runCatching { recording?.stop() }
        recording = null
        bound = false
        imageCapture = null
        videoCapture = null
        camera = null
        runCatching { provider?.unbindAll() }
    }

    fun release() {
        unbind()
        runCatching { effect?.close() }
        effect = null
        pipeline.close()
        latestFrame?.bitmap?.let { if (!it.isRecycled) it.recycle() }
        latestFrame = null
        analysisExecutor.shutdown()
    }

    fun setTorch(on: Boolean) {
        runCatching { camera?.cameraControl?.enableTorch(on) }
    }

    fun hasTorch(): Boolean = camera?.cameraInfo?.hasFlashUnit() == true

    fun zoomBy(factor: Float) {
        val info = camera?.cameraInfo ?: return
        val state = info.zoomState.value ?: return
        val target = (state.zoomRatio * factor).coerceIn(1f, min(state.maxZoomRatio, 6f))
        runCatching { camera?.cameraControl?.setZoomRatio(target) }
    }

    fun focus(normalizedX: Float, normalizedY: Float) {
        val control = camera?.cameraControl ?: return
        runCatching {
            val point = MeteringPointFactory.createPoint(
                normalizedX.coerceIn(0.02f, 0.98f),
                normalizedY.coerceIn(0.02f, 0.98f)
            )
            control.startFocusAndMetering(FocusMeteringAction.Builder(point).build())
        }
    }

    fun captureStill(file: File, onDone: (Boolean) -> Unit) {
        val capture = imageCapture ?: return onDone(false)
        val options = ImageCapture.OutputFileOptions.Builder(file).build()
        capture.takePicture(options, mainExecutor, object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) = onDone(true)
            override fun onError(exception: ImageCaptureException) = onDone(false)
        })
    }

    fun startRecording(file: File, muted: Boolean, onMessage: (String) -> Unit): Boolean {
        val video = videoCapture ?: return false
        if (recording != null) return false
        val free = file.parentFile?.usableSpace ?: 0L
        if (free in 1 until 300L * 1024L * 1024L) {
            onMessage("Storage is nearly full. A recording needs about 300 MB of room.")
            return false
        }
        var pending = video.output.prepareRecording(context, FileOutputOptions.Builder(file).build())
        val micAllowed = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (!muted && micAllowed) {
            pending = runCatching { pending.withAudioEnabled() }.getOrElse { pending }
        }
        recording = pending.start(mainExecutor) { event ->
            onRecordEvent(event)
            if (event is VideoRecordEvent.Finalize) recording = null
        }
        return true
    }

    fun pauseRecording() {
        runCatching { recording?.pause() }
    }

    fun resumeRecording() {
        runCatching { recording?.resume() }
    }

    fun stopRecording() {
        runCatching { recording?.stop() }
        recording = null
    }

    fun thermalStatus(): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return 0
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return 0
        return runCatching { power.currentThermalStatus }.getOrDefault(0)
    }

    fun displayRotation(): Int {
        val manager = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        return manager?.getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: Surface.ROTATION_0
    }

    /** How many degrees the sensor buffer must turn to stand upright on screen. */
    fun uprightRotation(): Int {
        val info = camera?.cameraInfo ?: return 0
        val displayDegrees = when (displayRotation()) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        val sensor = info.sensorRotationDegrees
        return if (info.lensFacing == CameraSelector.LENS_FACING_FRONT) {
            (sensor + displayDegrees) % 360
        } else {
            (sensor - displayDegrees + 360) % 360
        }
    }

    companion object {

        fun sharpness(bitmap: Bitmap): Double {
            val small = Bitmap.createScaledBitmap(bitmap, 96, 96, true)
            val w = small.width
            val h = small.height
            val pixels = IntArray(w * h)
            small.getPixels(pixels, 0, w, 0, 0, w, h)
            val luma = DoubleArray(w * h)
            for (i in pixels.indices) {
                val c = pixels[i]
                luma[i] = 0.299 * android.graphics.Color.red(c) +
                    0.587 * android.graphics.Color.green(c) +
                    0.114 * android.graphics.Color.blue(c)
            }
            var sum = 0.0
            var sumSq = 0.0
            var count = 0
            for (y in 1 until h - 1) {
                for (x in 1 until w - 1) {
                    val value = 4 * luma[y * w + x] -
                        luma[(y - 1) * w + x] - luma[(y + 1) * w + x] -
                        luma[y * w + x - 1] - luma[y * w + x + 1]
                    sum += value
                    sumSq += value * value
                    count++
                }
            }
            if (count == 0) return 0.0
            val mean = sum / count
            return sumSq / count - mean * mean
        }

        fun loadScaled(file: File, maxPixels: Int): Bitmap? = runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            val longest = max(bounds.outWidth, bounds.outHeight)
            var sample = 1
            while (longest / sample > maxPixels) sample *= 2
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            BitmapFactory.decodeFile(file.absolutePath, options)
        }.getOrNull()
    }
}

private suspend fun ListenableFuture<ProcessCameraProvider>.awaitProvider(
    executor: Executor
): ProcessCameraProvider? = suspendCancellableCoroutine { continuation ->
    addListener({ continuation.resume(runCatching { get() }.getOrNull()) }, executor)
}
