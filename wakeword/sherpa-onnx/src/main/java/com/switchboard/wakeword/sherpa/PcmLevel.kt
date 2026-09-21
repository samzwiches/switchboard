package com.switchboard.wakeword.sherpa

import kotlin.math.ln
import kotlin.math.sqrt

internal object PcmLevel {
    fun normalized(samples: ShortArray, count: Int): Float {
        if (count <= 0) return 0f
        var squareSum = 0.0
        repeat(count.coerceAtMost(samples.size)) { index ->
            val value = samples[index].toDouble()
            squareSum += value * value
        }
        val rootMeanSquare = sqrt(squareSum / count.coerceAtMost(samples.size).coerceAtLeast(1))
        if (rootMeanSquare <= 1.0) return 0f
        val decibelsFullScale = 20.0 * (ln(rootMeanSquare / Short.MAX_VALUE) / ln(10.0))
        return ((decibelsFullScale + 60.0) / 60.0).toFloat().coerceIn(0f, 1f)
    }
}
