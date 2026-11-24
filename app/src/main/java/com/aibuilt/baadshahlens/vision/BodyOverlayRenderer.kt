package com.aibuilt.baadshahlens.vision

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import com.aibuilt.baadshahlens.data.settings.Gender
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/**
 * Renders the nude body template on the GL overlay canvas, warped onto the tracked joints.
 */
class BodyOverlayRenderer {

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val blendPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val layerPaint = Paint()

    data class Pose(
        val uprightWidth: Float,
        val uprightHeight: Float,
        val matrix: Matrix
    )

    fun render(
        canvas: Canvas,
        frame: VisionPipeline.Frame,
        rotationDegrees: Int,
        strength: Float,
        gender: Gender,
        globalAlpha: Float
    ) {
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        val w = canvas.width.toFloat()
        val h = canvas.height.toFloat()
        val pose = mapping(rotationDegrees, w, h)
        val confidence = if (frame.present) 1f else (1f - (frame.ageMs / 3200f)).coerceIn(0f, 1f)
        val alpha = (strength * globalAlpha * confidence * if (frame.present) 1f else 0.55f)
            .coerceIn(0f, 1f)
        if (alpha <= 0.02f || frame.landmarks.size < 29) return

        val skin = frame.skin
        val src = if (skin.valid) skin.color else Color.rgb(214, 172, 142)

        canvas.save()
        canvas.concat(pose.matrix)
        val uw = pose.uprightWidth
        val uh = pose.uprightHeight
        drawBody(canvas, frame, uw, uh, alpha, gender, src, skin.lightX)
        drawRealPixels(canvas, frame, uw, uh)
        canvas.restore()
    }

    /** White-on-black mask of the area to regenerate in the cloud, at the still's own size. */
    fun renderMask(frame: VisionPipeline.Frame, width: Int, height: Int, rotationDegrees: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.BLACK)
        val pose = mapping(rotationDegrees, width.toFloat(), height.toFloat())
        canvas.save()
        canvas.concat(pose.matrix)
        fillPaint.color = Color.WHITE
        fillPaint.style = Paint.Style.FILL
        fillPaint.shader = null
        val uw = pose.uprightWidth
        val uh = pose.uprightHeight
        torsoPath(frame, uw, uh, Gender.MALE).let { canvas.drawPath(it, fillPaint) }
        legsPaths(frame, uw, uh).forEach { canvas.drawPath(it, fillPaint) }
        armPaths(frame, uw, uh).forEach { canvas.drawPath(it, fillPaint) }
        canvas.restore()
        return bitmap
    }

    private fun drawBody(
        canvas: Canvas,
        frame: VisionPipeline.Frame,
        uw: Float,
        uh: Float,
        alpha: Float,
        gender: Gender,
        skinColor: Int,
        lightX: Float
    ) {
        val shade = if (abs(lightX) < 0.08f) 0f else lightX
        val bright = scale(skinColor, 1.08f)
        val dark = scale(skinColor, 0.80f)

        armPaths(frame, uw, uh).forEach { drawPart(canvas, it, skinColor, bright, dark, shade, alpha * 0.94f) }
        legsPaths(frame, uw, uh).forEach { drawPart(canvas, it, skinColor, bright, dark, shade, alpha) }
        drawPart(canvas, torsoPath(frame, uw, uh, gender), skinColor, bright, dark, shade, alpha, chestMass(gender))
        drawNavel(canvas, frame, uw, uh, dark, alpha)
    }

    private fun drawPart(
        canvas: Canvas,
        path: Path,
        core: Int,
        bright: Int,
        dark: Int,
        shade: Float,
        alpha: Float,
        chestMass: Float = 0f
    ) {
        val bounds = RectF()
        path.computeBounds(bounds, true)
        if (bounds.width() < 2f || bounds.height() < 2f) return
        val centerX = bounds.centerX()
        val centerY = bounds.centerY()
        val radius = max(bounds.width(), bounds.height()) * 0.72f

        fillPaint.shader = RadialGradient(
            centerX, centerY, radius,
            intArrayOf(withAlpha(core, alpha), withAlpha(core, alpha * 0.92f)),
            floatArrayOf(0.62f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawPath(path, fillPaint)

        fillPaint.shader = LinearGradient(
            centerX - radius * shade, centerY - radius,
            centerX + radius * shade, centerY + radius,
            withAlpha(bright, alpha * 0.55f),
            withAlpha(dark, alpha * 0.55f),
            Shader.TileMode.CLAMP
        )
        canvas.drawPath(path, fillPaint)
        fillPaint.shader = null

        if (chestMass > 0f) {
            shadowPaint.color = withAlpha(dark, alpha * 0.5f)
            shadowPaint.style = Paint.Style.FILL
            canvas.drawCircle(centerX - bounds.width() * 0.18f, centerY - bounds.height() * 0.08f, chestMass, shadowPaint)
            canvas.drawCircle(centerX + bounds.width() * 0.18f, centerY - bounds.height() * 0.08f, chestMass, shadowPaint)
        }
    }

    private fun drawNavel(canvas: Canvas, frame: VisionPipeline.Frame, uw: Float, uh: Float, dark: Int, alpha: Float) {
        val hipL = frame.point(23)
        val hipR = frame.point(24)
        val navX = ((hipL.x + hipR.x) / 2f) * uw
        val navY = ((hipL.y + hipR.y) / 2f) * uh - uh * 0.03f
        shadowPaint.color = withAlpha(dark, alpha * 0.85f)
        shadowPaint.style = Paint.Style.FILL
        canvas.drawCircle(navX, navY, uh * 0.0055f + 1.2f, shadowPaint)
    }

    private fun drawRealPixels(canvas: Canvas, frame: VisionPipeline.Frame, uw: Float, uh: Float) {
        val rect = RectF(0f, 0f, uw, uh)
        val checkpoint = canvas.saveLayer(rect, layerPaint)
        blendPaint.alpha = 255
        canvas.drawBitmap(frame.bitmap, null, rect, blendPaint)
        maskPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        maskPaint.isFilterBitmap = true
        canvas.drawBitmap(frame.occluders, null, rect, maskPaint)
        maskPaint.xfermode = null
        canvas.restoreToCount(checkpoint)
    }

    private fun torsoPath(frame: VisionPipeline.Frame, uw: Float, uh: Float, gender: Gender): Path {
        val sl = px(frame.point(11), uw, uh)
        val sr = px(frame.point(12), uw, uh)
        val hl = px(frame.point(23), uw, uh)
        val hr = px(frame.point(24), uh, uh)
        val waist = if (gender == Gender.FEMALE) 0.30f else 0.20f
        val hipFlare = if (gender == Gender.FEMALE) 0.16f else 0.08f
        val chest = if (gender == Gender.FEMALE) 0.24f else 0.12f

        val leftWaist = lerp(sl, hl, 0.55f)
        val rightWaist = lerp(sr, hr, 0.55f)
        val centerMid = lerp(leftWaist, rightWaist, 0.5f)
        val shoulderCenter = lerp(sl, sr, 0.5f)

        val leftWaistIn = lerp(leftWaist, centerMid, waist)
        val rightWaistIn = lerp(rightWaist, centerMid, waist)
        val leftChest = lerp(lerp(sl, leftWaistIn, 0.45f), centerMid, -chest * 0.4f)
        val rightChest = lerp(lerp(sr, rightWaistIn, 0.45f), centerMid, -chest * 0.4f)

        val leftHip = lerp(hl, centerMid, -hipFlare)
        val rightHip = lerp(hr, centerMid, -hipFlare)

        val path = Path()
        path.moveTo(shoulderCenter.x, shoulderCenter.y - uh * 0.012f)
        path.quadTo(leftChest.x, leftChest.y, leftWaistIn.x, leftWaistIn.y)
        path.quadTo(leftHip.x, leftHip.y, hl.x, hl.y)
        path.lineTo(lerp(hl, hr, 0.5f).x, lerp(hl, hr, 0.5f).y)
        path.lineTo(hr.x, hr.y)
        path.quadTo(rightHip.x, rightHip.y, rightWaistIn.x, rightWaistIn.y)
        path.quadTo(rightChest.x, rightChest.y, sr.x, sr.y)
        path.close()
        return path
    }

    private fun armPaths(frame: VisionPipeline.Frame, uw: Float, uh: Float): List<Path> {
        val paths = mutableListOf<Path>()
        limb(frame, uw, uh, 11, 13, 15, uh * 0.030f, uh * 0.022f, uh * 0.017f)?.let { paths += it }
        limb(frame, uw, uh, 12, 14, 16, uh * 0.030f, uh * 0.022f, uh * 0.017f)?.let { paths += it }
        return paths
    }

    private fun legsPaths(frame: VisionPipeline.Frame, uw: Float, uh: Float): List<Path> {
        val paths = mutableListOf<Path>()
        limb(frame, uw, uh, 23, 25, 27, uh * 0.048f, uh * 0.034f, uh * 0.022f)?.let { paths += it }
        limb(frame, uw, uh, 24, 26, 28, uh * 0.048f, uh * 0.034f, uh * 0.022f)?.let { paths += it }
        return paths
    }

    private fun limb(
        frame: VisionPipeline.Frame,
        uw: Float,
        uh: Float,
        a: Int,
        b: Int,
        c: Int,
        widthA: Float,
        widthB: Float,
        widthC: Float
    ): Path? {
        val start = px(frame.point(a), uw, uh)
        val middle = px(frame.point(b), uw, uh)
        val end = px(frame.point(c), uw, uh)
        if (hypot(start.x - middle.x, start.y - middle.y) < 3f) return null
        val path = Path()
        addTaper(path, start, middle, widthA, widthB)
        addTaper(path, middle, end, widthB, widthC)
        return path
    }

    private fun addTaper(path: Path, from: android.graphics.PointF, to: android.graphics.PointF, wFrom: Float, wTo: Float) {
        val dx = to.x - from.x
        val dy = to.y - from.y
        val length = hypot(dx, dy)
        if (length < 1f) return
        val nx = -dy / length
        val ny = dx / length
        path.moveTo(from.x + nx * wFrom, from.y + ny * wFrom)
        path.lineTo(to.x + nx * wTo, to.y + ny * wTo)
        path.lineTo(to.x - nx * wTo, to.y - ny * wTo)
        path.lineTo(from.x - nx * wFrom, from.y - ny * wFrom)
        path.close()
    }

    private fun mapping(rotationDegrees: Int, w: Float, h: Float): Pose {
        val matrix = Matrix()
        val uprightWidth: Float
        val uprightHeight: Float
        when (rotationDegrees) {
            90 -> {
                matrix.postRotate(90f)
                matrix.postTranslate(w, 0f)
                uprightWidth = h
                uprightHeight = w
            }
            180 -> {
                matrix.postRotate(180f)
                matrix.postTranslate(w, h)
                uprightWidth = w
                uprightHeight = h
            }
            270 -> {
                matrix.postRotate(270f)
                matrix.postTranslate(0f, h)
                uprightWidth = h
                uprightHeight = w
            }
            else -> {
                uprightWidth = w
                uprightHeight = h
            }
        }
        return Pose(uprightWidth, uprightHeight, matrix)
    }

    private fun px(point: android.graphics.PointF, uw: Float, uh: Float) =
        android.graphics.PointF(point.x * uw, point.y * uh)

    private fun lerp(a: android.graphics.PointF, b: android.graphics.PointF, t: Float) =
        android.graphics.PointF(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)

    private fun chestMass(gender: Gender): Float = if (gender == Gender.FEMALE) 12f else 7f

    private fun withAlpha(color: Int, alpha: Float): Int =
        Color.argb((alpha.coerceIn(0f, 1f) * 255).toInt(), Color.red(color), Color.green(color), Color.blue(color))

    private fun scale(color: Int, factor: Float): Int = Color.rgb(
        (Color.red(color) * factor).toInt().coerceIn(0, 255),
        (Color.green(color) * factor).toInt().coerceIn(0, 255),
        (Color.blue(color) * factor).toInt().coerceIn(0, 255)
    )
}
