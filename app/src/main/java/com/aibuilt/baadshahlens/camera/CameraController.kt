package com.aibuilt.baadshahlens.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Rational
import android.view.Surface
import androidx.camera.core.Camera
import androidx.camera.core.CameraEffect
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.ResolutionSelector
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
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
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.min

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

    suspend fun bind(previewView: PreviewView, lensFacing: Int): String? {
        if (bound) return null
        if (!pipeline.isReady) return "Tracking models are not downloaded yet."
        val cameraProvider = ProcessCameraProvider.getInstance(context).awaitProvider() ?: return "The camera could not start."
        provider = cameraProvider

        val effectTargets = CameraEffect.PREVIEW or CameraEffect.VIDEO_CAPTURE or CameraEffect.IMAGE_CAPTURE
        val overlayEffect = OverlayEffect(effectTargets, 2, handler, Consumer { it.printStackTrace() })
        overlayEffect.setOnDrawListener { frame ->
            val latest = latestFrame
            val canvas = frame.canvas
            if (latest != null && overlayOn) {
                renderer.render(canvas, latest, frame.rotationDegrees, strength, gender, ghostAlpha)
            } else {
                canvas.drawColor(0, android.graphics.PorterDuff.Mode.CLEAR)
            }
            true
        }
        effect = overlayEffect

        val preview = Preview.Builder().build().also {
            it.surfaceProvider = previewView.surfaceProvider
        }
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        androidx.camera.core.resolutionselector.ResolutionStrategy(
                            android.util.Size(640, 480),
                            androidx.camera.core.resolutionselector.ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
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

        val capture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()
        imageCapture = capture

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
        val viewPort = ViewPort.Builder(Rational(3, 4), rotation).build()

        val group = UseCaseGroup.Builder()
            .addUseCase(preview)
            .addUseCase(analysis)
            .addUseCase(capture)
            .addUseCase(video)
            .addEffect(overlayEffect)
            .setViewPort(viewPort)
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

    fun setZoom(ratio: Float) {
        val info = camera?.cameraInfo ?: return
        val zoom = info.zoomState.value ?: return
        runCatching {
            camera?.cameraControl?.setZoomRatio(ratio.coerceIn(zoom.minZoomRatio, min(zoom.maxZoomRatio, 6f)))
        }
    }

    fun focus(x: Float, y: Float) {
        val control = camera?.cameraControl ?: return
        runCatching {
            val point = androidx.camera.core.MeteringPointFactory.createPoint(0f, 0f)
            val factory = surfacFactory() ?: return
            val meteringPoint = factory.createPoint(x, y)
            control.startFocusAndMetering(FocusMeteringAction.Builder(meteringPoint).build())
        }
    }

    private var pointFactory: androidx.camera.core.MeteringPointFactory? = null

    fun attachMetering(width: Int, height: Int) {
        pointFactory = androidx.camera.core.MeteringPointFactory.createPoint(0f, 0f).let {
            androidx.camera.core.MeteringPointFactory(androidx.camera.core.CameraSelector.LENS_FACING_BACK)
                .let { _ -> null }
        }
        pointFactory = if (width > 0 && height > 0) null else null
        cropMetering = if (width > 0 && height > 0) {
            androidx.camera.core.MeteringPointFactory(width, height)
        } else {
            null
        }
    }

    private var cropMetering: androidx.camera.core.MeteringPointFactory? = null

    private fun surfacFactory(): androidx.camera.core.MeteringPointFactory? =
        cropMetering ?: androidx.camera.core.MeteringPointFactory(640, 480)

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
        val freeBytes = file.parentFile?.usableSpace ?: 0L
        if (freeBytes in 1 until 300L * 1024L * 1024L) {
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
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return 0
        return runCatching { power.currentThermalStatus }.getOrDefault(0)
    }

    fun displayRotation(): Int {
        val manager = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        return manager?.getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: Surface.ROTATION_0
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
                luma[i] = (0.299 * android.graphics.Color.red(c) +
                    0.587 * android.graphics.Color.green(c) +
                    0.114 * android.graphics.Color.blue(c))
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

private const val Display = android.view.Display::class

private suspend fun ListenableFuture<ProcessCameraProvider>.awaitProvider(): ProcessCameraProvider? =
    suspendCancellableCoroutine { continuation ->
        addListener({
            continuation.resume(runCatching { get() }.getOrNull())
        }, ContextCompat.getMainExecutorSafe())
    }
