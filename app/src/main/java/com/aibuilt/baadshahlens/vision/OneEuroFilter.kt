package com.aibuilt.baadshahlens.vision

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * One-Euro filter: heavy smoothing at low speed, almost no lag at high speed.
 */
class OneEuroFilter(
    private val minCutoff: Float = 1.2f,
    private val beta: Float = 0.02f,
    private val derivativeCutoff: Float = 1.0f
) {
    private var hasValue = false
    private var lastValue = 0f
    private var lastDerivative = 0f

    fun reset() {
        hasValue = false
        lastDerivative = 0f
    }

    fun filter(value: Float, dtSeconds: Float): Float {
        val dt = if (dtSeconds <= 0f) 0.033f else dtSeconds
        if (!hasValue) {
            hasValue = true
            lastValue = value
            return value
        }
        val derivative = (value - lastValue) / dt
        val smoothedDerivative = lowPass(derivative, lastDerivative, alpha(derivativeCutoff, dt))
        lastDerivative = smoothedDerivative
        val cutoff = minCutoff + beta * abs(smoothedDerivative)
        val result = lowPass(value, lastValue, alpha(cutoff, dt))
        lastValue = result
        return result
    }

    private fun alpha(cutoff: Float, dt: Float): Float {
        val tau = 1f / (2f * PI.toFloat() * cutoff)
        return 1f / (1f + tau / dt)
    }

    private fun lowPass(value: Float, previous: Float, a: alphaType): Float =
        previous + a.value * (value - previous)

    private data class alphaType(val value: Float)

    private fun lowPass(value: Float, previous: Float, a: Float): Float =
        previous + a * (value - previous)

    companion object {
        fun distance(a: Pair<Float, Float>, b: Pair<Float, Float>): Float {
            val dx = a.first - b.first
            val dy = a.second - b.second
            return sqrt(dx * dx + dy * dy)
        }
    }
}
