package com.aibuilt.baadshahlens.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.util.Log
import androidx.camera.core.ImageProxy
import com.aibuilt.baadshahlens.data.settings.Gender
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.imagesegmenter.ImageSegmenter
import com.google.mediapipe.tasks.vision.imagesegmenter.ImageSegmenterOptions
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerOptions
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Per-frame body understanding: pose landmarks on a crop around the tracked person,
 * a class mask for clothes/skin/hair, skin colour sampling and track locking.
 */
class VisionPipeline {

    companion object {
        const val ANALYSIS_SIZE = 256
        const val MASK_SIZE = 64
        const val CLASS_BACKGROUND = 0
        const val CLASS_HAIR = 1
        const val CLASS_BODY_SKIN = 2
        const val CLASS_FACE_SKIN = 3
        const val CLASS_CLOTHES = 4
        const val CLASS_ACCESSORY = 5
        private const val LOST_HOLD_MS = 3000L
        private const val POSE_EVERY = 2
    }

    data class SkinSample(
        val color: Int = Color.rgb(214, 172, 142),
        val lightX: Float = 0f,
        val lightY: Float = -1f,
        val brightness: Float = 0.6f,
        val valid: Boolean = false
    )

    data class Frame(
        val bitmap: Bitmap,
        val landmarks: List<PointF>,
        val present: Boolean,
        val confidence: Float,
        val ageMs: Long,
        val area: Float,
        val classes: IntArray,
        val occluders: Bitmap,
        val skin: SkinSample
    ) {
        fun point(index: Int): PointF = landmarks.getOrElse(index) { PointF(0.5f, 0.5f) }
    }

    private var poseLandmarker: PoseLandmarker? = null
    private var segmenter: ImageSegmenter? = null
    private var ready = false

    private val filters = HashMap<Int, OneEuroFilter>()
    private val lastLandmarks = HashMap<Int, PointF>()
    private var lastSeenAt = 0L
    private var lastConfidence = 0f
    private var lastArea = 0f
    private var lastCentroid: PointF? = null
    private var lastRoi: RectF? = null
    private var previousLuma: ByteArray? = null
    private var frameCounter = 0
    private var genderHint: Gender? = null

    val isReady: Boolean get() = ready

    fun initialize(context: Context, poseModel: File?, segModel: File?): Boolean {
        if (ready) return true
        if (poseModel == null || segModel == null) return false
        if (!poseModel.exists() || !segModel.exists()) return false
        return runCatching {
            val poseOptions = PoseLandmarkerOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelBuffer(bufferOf(poseModel)).build())
                .setRunningMode(RunningMode.IMAGE)
                .setNumPoses(2)
                .setMinPoseDetectionConfidence(0.5f)
                .setMinPosePresenceConfidence(0.5f)
                .setMinTrackingConfidence(0.5f)
                .build()
            poseLandmarker = PoseLandmarker.createFromOptions(context, poseOptions)

            val segOptions = ImageSegmenterOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelBuffer(bufferOf(segModel)).build())
                .setRunningMode(RunningMode.IMAGE)
                .setOutputCategoryMask(true)
                .setOutputConfidenceMasks(false)
                .build()
            segmenter = ImageSegmenter.createFromOptions(context, segOptions)
            ready = true
        }.onFailure { Log.w("VisionPipeline", "init failed", it) }.isSuccess
    }

    fun close() {
        runCatching { poseLandmarker?.close() }
        runCatching { segmenter?.close() }
        poseLandmarker = null
        segmenter = null
        ready = false
    }

    fun suggestedGender(): Gender? = genderHint

    fun analyze(proxy: ImageProxy): Frame? {
        if (!ready) {
            proxy.close()
            return null
        }
        val rotation = proxy.imageInfo.rotationDegrees
        return try {
            val source = proxy.toBitmap()
            val scaled = uprightScaled(source, rotation)
            val stamp = System.currentTimeMillis()
            val luma = lumaOf(scaled)
            val shift = estimateShift(previousLuma, luma)
            previousLuma = luma

            val runPose = frameCounter % POSE_EVERY == 0 || lastLandmarks.isEmpty()
            frameCounter++

            if (runPose) {
                val found = detectPose(scaled, stamp)
                if (found != null) {
                    mergePose(found, stamp)
                } else if (lastLandmarks.isNotEmpty() && stamp - lastSeenAt < LOST_HOLD_MS) {
                    carry(shift, stamp)
                } else {
                    lastLandmarks.clear()
                    lastRoi = null
                    lastConfidence = 0f
                    lastArea = 0f
                }
            } else if (lastLandmarks.isNotEmpty()) {
                carry(shift, stamp)
            }

            val present = lastLandmarks.isNotEmpty() && stamp - lastSeenAt < 400
            val confidence = if (present) lastConfidence else 0f
            val skin = sampleSkin(scaled, present)
            val classes = if (present) segment(scaled) else null
            val occluders = occluderMask(classes, present)

            Frame(
                bitmap = scaled,
                landmarks = orderedLandmarks(),
                present = present,
                confidence = confidence,
                ageMs = if (present) 0 else stamp - lastSeenAt,
                area = lastArea,
                classes = classes ?: IntArray(ANALYSIS_SIZE * ANALYSIS_SIZE),
                occluders = occluders,
                skin = skin
            )
        } catch (t: Throwable) {
            Log.w("VisionPipeline", "frame failed", t)
            null
        } finally {
            runCatching { proxy.close() }
        }
    }

    // region pose

    private fun detectPose(bitmap: Bitmap, stamp: Long): List<PointF>? {
        val landmarker = poseLandmarker ?: return null
        val roi = lastRoi?.let { expand(it, 0.40f) } ?: RectF(0f, 0f, 1f, 1f)
        val rect = Rect(
            (roi.left * bitmap.width).toInt().coerceIn(0, bitmap.width - 16),
            (roi.top * bitmap.height).toInt().coerceIn(0, bitmap.height - 16),
            (roi.right * bitmap.width).toInt().coerceIn(16, bitmap.width),
            (roi.bottom * bitmap.height).toInt().coerceIn(16, bitmap.height)
        )
        if (rect.width() < 32 || rect.height() < 32) return null
        val crop = Bitmap.createBitmap(bitmap, rect.left, rect.top, rect.width(), rect.height())
        val image: MPImage = BitmapImageBuilder(crop).build()
        val result = landmarker.detect(image)
        image.close()
        val poses = result.landmarks()
        if (poses.isEmpty()) return null

        var best: List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>? = null
        var bestScore = -1f
        for (candidate in poses) {
            val box = boundsOf(candidate)
            val area = box.width() * box.height()
            var score = area
            lastCentroid?.let { previous ->
                val distance = OneEuroFilter.distance(
                    previous,
                    PointF(box.centerX(), box.centerY())
                )
                score *= if (distance < 0.22f) 1.8f else 0.7f
            }
            if (score > bestScore) {
                bestScore = score
                best = candidate
            }
        }
        val chosen = best ?: return null
        lastRoi = boundsOf(chosen)
        return chosen.map { PointF(roi.left + it.x() * roi.width(), roi.top + it.y() * roi.height()) }
    }

    private fun mergePose(points: List<PointF>, stamp: Long) {
        val dt = if (lastSeenAt == 0L) 0.033f else ((stamp - lastSeenAt) / 1000f).coerceIn(0.008f, 0.2f)
        var visibilitySum = 0f
        points.forEachIndexed { index, point ->
            val filter = filters.getOrPut(index) { OneEuroFilter() }
            val dx = filter.filter(point.x, dt)
            val dy = filters.getOrPut(index + 100) { OneEuroFilter() }.filter(point.y, dt)
            lastLandmarks[index] = PointF(dx, dy)
            visibilitySum += 1f
        }
        val box = boundsOf(lastLandmarks)
        lastArea = box.width() * box.height()
        lastCentroid = PointF(box.centerX(), box.centerY())
        lastConfidence = if (visibilitySum <= 0f) 0.6f else 0.72f
        lastSeenAt = stamp
        genderHint = inferGender()
    }

    private fun carry(shift: PointF, stamp: Long) {
        lastLandmarks.keys.toList().forEach { index ->
            val point = lastLandmarks[index] ?: return@forEach
            lastLandmarks[index] = PointF(
                (point.x + shift.x).coerceIn(-0.1f, 1.1f),
                (point.y + shift.y).coerceIn(-0.1f, 1.1f)
            )
        }
        val elapsed = stamp - lastSeenAt
        lastConfidence = (lastConfidence * 0.96f).coerceAtLeast(0.15f)
        lastRoi = boundsOf(lastLandmarks)
        if (elapsed > LOST_HOLD_MS) lastConfidence = 0f
    }

    private fun orderedLandmarks(): List<PointF> {
        if (lastLandmarks.isEmpty()) return emptyList()
        val maxIndex = lastLandmarks.keys.maxOrNull() ?: return emptyList()
        return (0..maxIndex).map { lastLandmarks[it] ?: lastLandmarks[maxIndex]!! }
    }

    private fun inferGender(): Gender? {
        val shoulder = OneEuroFilter.distance(
            Pair(lastLandmarks[11]?.x ?: return genderHint, lastLandmarks[11]?.y ?: return genderHint),
            Pair(lastLandmarks[12]?.x ?: return genderHint, lastLandmarks[12]?.y ?: return genderHint)
        )
        val hip = OneEuroFilter.distance(
            Pair(lastLandmarks[23]?.x ?: return genderHint, lastLandmarks[23]?.y ?: return genderHint),
            Pair(lastLandmarks[24]?.x ?: return genderHint, lastLandmarks[24]?.y ?: return genderHint)
        )
        if (shoulder <= 0f || hip <= 0f) return genderHint
        val ratio = shoulder / hip
        return if (ratio > 1.13f) Gender.MALE else Gender.FEMALE
    }

    // endregion

    // region segmentation and skin

    private fun segment(bitmap: Bitmap): IntArray? {
        val segmenter = segmenter ?: return null
        return runCatching {
            val image: MPImage = BitmapImageBuilder(bitmap).build()
            val result = segmenter.segment(image)
            image.close()
            val maskImage = result.categoryMask().orElse(null)
            val buffer: ByteBuffer? = maskImage?.getByteBuffer()
            val classes = IntArray(ANALYSIS_SIZE * ANALYSIS_SIZE)
            if (buffer != null) {
                for (i in classes.indices) {
                    classes[i] = if (i < buffer.limit()) (buffer.get(i).toInt() and 0xFF) else CLASS_BACKGROUND
                }
            }
            runCatching { result.close() }
            runCatching { maskImage?.close() }
            classes
        }.getOrNull()
    }

    private fun occluderMask(classes: IntArray?, present: Boolean): Bitmap {
        val bitmap = Bitmap.createBitmap(MASK_SIZE, MASK_SIZE, Bitmap.Config.ARGB_8888)
        if (!present) return bitmap
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.WHITE
        if (classes != null) {
            val pixels = IntArray(MASK_SIZE * MASK_SIZE)
            for (y in 0 until MASK_SIZE) {
                for (x in 0 until MASK_SIZE) {
                    val sx = (x * ANALYSIS_SIZE) / MASK_SIZE
                    val sy = (y * ANALYSIS_SIZE) / MASK_SIZE
                    val cls = classes[sy * ANALYSIS_SIZE + sx]
                    pixels[y * MASK_SIZE + x] =
                        if (cls == CLASS_HAIR || cls == CLASS_FACE_SKIN || cls == CLASS_ACCESSORY) {
                            Color.WHITE
                        } else {
                            Color.TRANSPARENT
                        }
                }
            }
            bitmap.setPixels(pixels, 0, MASK_SIZE, 0, 0, MASK_SIZE, MASK_SIZE)
        }
        listOf(15, 16, 19, 20).forEach { index ->
            val p = lastLandmarks[index] ?: return@forEach
            canvas.drawCircle(p.x * MASK_SIZE, p.y * MASK_SIZE, 3.4f, paint)
        }
        return bitmap
    }

    private fun sampleSkin(bitmap: Bitmap, present: Boolean): SkinSample {
        if (!present) return SkinSample()
        val points = mutableListOf<PointF>()
        listOf(0, 1, 2, 5, 9, 10, 17, 18).forEach { index -> lastLandmarks[index]?.let { points += it } }
        val r = 4
        var red = 0f
        var green = 0f
        var blue = 0f
        var count = 0
        var leftLuma = 0f
        var leftCount = 0
        var rightLuma = 0f
        var rightCount = 0
        for (p in points) {
            val cx = (p.x * bitmap.width).toInt()
            val cy = (p.y * bitmap.height).toInt()
            for (dy in -r..r step 2) {
                for (dx in -r..r step 2) {
                    val x = cx + dx
                    val y = cy + dy
                    if (x < 0 || y < 0 || x >= bitmap.width || y >= bitmap.height) continue
                    val c = bitmap.getPixel(x, y)
                    red += Color.red(c)
                    green += Color.green(c)
                    blue += Color.blue(c)
                    count++
                    val luma = 0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)
                    if (dx < 0) {
                        leftLuma += luma
                        leftCount++
                    } else if (dx > 0) {
                        rightLuma += luma
                        rightCount++
                    }
                }
            }
        }
        if (count == 0) return SkinSample()
        val color = Color.rgb(
            (red / count).toInt().coerceIn(0, 255),
            (green / count).toInt().coerceIn(0, 255),
            (blue / count).toInt().coerceIn(0, 255)
        )
        val leftAvg = if (leftCount == 0) 0f else leftLuma / leftCount
        val rightAvg = if (rightCount == 0) 0f else rightLuma / rightCount
        val total = leftAvg + rightAvg
        val lightX = if (total <= 1f) 0f else (rightAvg - leftAvg) / total
        val brightness = ((red + green + blue) / (3f * count)) / 255f
        return SkinSample(color, lightX, -1f, brightness, true)
    }

    // endregion

    // region bitmap helpers

    private fun uprightScaled(source: Bitmap, rotation: Int): Bitmap {
        val longest = max(source.width, source.height)
        val scale = ANALYSIS_SIZE.toFloat() / longest.toFloat()
        val matrix = Matrix()
        matrix.postRotate(rotation.toFloat())
        if (scale < 1f) matrix.postScale(scale, scale)
        val rotated = if (rotation == 0 && scale >= 1f) {
            source
        } else {
            Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
        }
        if (rotated.width == ANALYSIS_SIZE && rotated.height == ANALYSIS_SIZE) return rotated
        val square = Bitmap.createBitmap(ANALYSIS_SIZE, ANALYSIS_SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(square)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        val offsetX = (ANALYSIS_SIZE - rotated.width) / 2f
        val offsetY = (ANALYSIS_SIZE - rotated.height) / 2f
        canvas.drawBitmap(rotated, offsetX, offsetY, paint)
        if (rotated !== source && rotated !== square) rotated.recycle()
        return square
    }

    private fun lumaOf(bitmap: Bitmap): ByteArray {
        val w = 32
        val h = 32
        val small = Bitmap.createScaledBitmap(bitmap, w, h, true)
        val pixels = IntArray(w * h)
        small.getPixels(pixels, 0, w, 0, 0, w, h)
        val out = ByteArray(w * h)
        for (i in pixels.indices) {
            val c = pixels[i]
            val luma = (0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)).toInt()
            out[i] = luma.coerceIn(0, 255).toByte()
        }
        return out
    }

    /** Cheap block match: returns the dominant frame-to-frame translation in normalized units. */
    private fun estimateShift(previous: ByteArray?, current: ByteArray): PointF {
        if (previous == null || previous.size != current.size) return PointF(0f, 0f)
        val size = 11
        val w = 32
        val h = 32
        var bestX = 0
        var bestY = 0
        var bestScore = Long.MAX_VALUE
        for (shiftY in -4..4) {
            for (shiftX in -4..4) {
                var score = 0L
                for (y in size until h - size step 4) {
                    for (x in size until w - size step 4) {
                        val diff = abs(
                            (current[y * w + x].toInt() and 0xFF) -
                                (previous[(y + shiftY) * w + (x + shiftX)].toInt() and 0xFF)
                        )
                        score += diff.toLong()
                    }
                }
                if (score < bestScore) {
                    bestScore = score
                    bestX = shiftX
                    bestY = shiftY
                }
            }
        }
        return PointF(bestX / w.toFloat(), bestY / h.toFloat())
    }

    private fun boundsOf(points: List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>): RectF {
        var minX = 1f
        var minY = 1f
        var maxX = 0f
        var maxY = 0f
        for (p in points) {
            minX = min(minX, p.x())
            minY = min(minY, p.y())
            maxX = max(maxX, p.x())
            maxY = max(maxY, p.y())
        }
        return RectF(minX, minY, maxX, maxY)
    }

    private fun boundsOf(points: Map<Int, PointF>): RectF {
        if (points.isEmpty()) return RectF(0.4f, 0.4f, 0.6f, 0.6f)
        var minX = 1f
        var minY = 1f
        var maxX = 0f
        var maxY = 0f
        for (p in points.values) {
            minX = min(minX, p.x)
            minY = min(minY, p.y)
            maxX = max(maxX, p.x)
            maxY = max(maxY, p.y)
        }
        return RectF(minX, minY, maxX, maxY)
    }

    private fun expand(rect: RectF, factor: Float): RectF {
        val dx = rect.width() * factor
        val dy = rect.height() * factor
        return RectF(rect.left - dx, rect.top - dy, rect.right + dx, rect.bottom + dy)
    }

    private fun bufferOf(file: File): ByteBuffer {
        val bytes = file.readBytes()
        val buffer = ByteBuffer.allocateDirect(bytes.size)
        buffer.put(bytes)
        buffer.rewind()
        return buffer
    }

    // endregion
}
