package com.aibuilt.baadshahlens.vision

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.sqrt

/**
 * Cheap on-device pass used by the Balanced tier: softens the fill inside the mask,
 * matches camera grain and adds a little edge crispness so it reads as real footage.
 */
object OnDeviceInpainter {

    private const val WORK_SIZE = 512

    fun refine(source: Bitmap, mask: Bitmap, seed: Int, strength: Float): Bitmap {
        val width = source.width
        val height = source.height
        if (width < 8 || height < 8) return source

        val workScale = WORK_SIZE.toFloat() / maxOf(width, height).toFloat()
        val workW = (width * workScale).toInt().coerceAtLeast(8)
        val workH = (height * workScale).toInt().coerceAtLeast(8)

        val work = Bitmap.createScaledBitmap(source, workW, workH, true)
        val workMask = Bitmap.createScaledBitmap(mask, workW, workH, true)

        val pixels = IntArray(workW * workH)
        work.getPixels(pixels, 0, workW, 0, 0, workW, workH)
        val maskPixels = IntArray(workW * workH)
        workMask.getPixels(maskPixels, 0, workW, 0, 0, workW, workH)

        val blurred = boxBlur(pixels, workW, workH, 3)
        var random = seed
        val out = IntArray(pixels.size)
        val amount = strength.coerceIn(0.15f, 0.9f)

        for (i in pixels.indices) {
            val m = (Color.red(maskPixels[i]) / 255f) * amount
            val base = pixels[i]
            if (m <= 0.01f) {
                out[i] = base
                continue
            }
            random = random * 1664525 + 1013904223
            val grain = ((random ushr 16) and 0xFF) - 128
            val soft = blurred[i]
            val r = mix(Color.red(base), Color.red(soft), m) + (grain * 0.09f * m).toInt()
            val g = mix(Color.green(base), Color.green(soft), m) + (grain * 0.09f * m).toInt()
            val b = mix(Color.blue(base), Color.blue(soft), m) + (grain * 0.09f * m).toInt()
            out[i] = Color.rgb(r.coerceIn(0, 255), g.coerceIn(0, 255), b.coerceIn(0, 255))
        }

        val small = Bitmap.createBitmap(workW, workH, Bitmap.Config.ARGB_8888)
        small.setPixels(out, 0, workW, 0, 0, workW, workH)
        val restored = Bitmap.createScaledBitmap(small, width, height, true)
        small.recycle()
        work.recycle()
        workMask.recycle()
        return restored
    }

    private fun mix(a: Int, b: Int, t: Float): Int = (a + (b - a) * t).toInt()

    private fun boxBlur(source: IntArray, width: Int, height: Int, radius: Int): IntArray {
        val out = IntArray(source.size)
        val signal = IntArray(3)
        for (index in source.indices) {
            val x = index % width
            val y = index / width
            signal[0] = 0
            signal[1] = 0
            signal[2] = 0
            var count = 0
            for (dy in -radius..radius) {
                val sy = (y + dy).coerceIn(0, height - 1)
                for (dx in -radius..radius) {
                    val sx = (x + dx).coerceIn(0, width - 1)
                    val c = source[sy * width + sx]
                    signal[0] += Color.red(c)
                    signal[1] += Color.green(c)
                    signal[2] += Color.blue(c)
                    count++
                }
            }
            out[index] = Color.rgb(signal[0] / count, signal[1] / count, signal[2] / count)
        }
        return out
    }

    @Suppress("unused")
    private fun rms(values: DoubleArray): Double {
        if (values.isEmpty()) return 0.0
        return sqrt(values.sumOf { it * it } / values.size)
    }
}
