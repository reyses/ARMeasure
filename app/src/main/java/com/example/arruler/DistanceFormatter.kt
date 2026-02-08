package com.example.arruler

import kotlin.math.abs

class DistanceFormatter {
    private val stringBuilder = StringBuilder()

    // Cache the last inputs and result to avoid re-formatting
    private var lastRounded: Long = -1L
    private var lastIsNegative: Boolean = false
    private var lastUnit: String? = null
    private var cachedResult: String? = null

    fun format(value: Float, unit: String): String {
        val isNegative = value < 0
        val absValue = abs(value)
        // Rounding Mode: HALF_UP
        // Adding 0.5 and casting to long achieves HALF_UP for positive numbers
        val rounded = (absValue * 10 + 0.5f).toLong()

        // Check if we can reuse the cached result
        if (rounded == lastRounded && isNegative == lastIsNegative && unit == lastUnit && cachedResult != null) {
            return cachedResult!!
        }

        stringBuilder.setLength(0)

        // Manual formatting to 1 decimal place to avoid String.format allocation
        if (isNegative) {
            stringBuilder.append('-')
        }

        val integerPart = rounded / 10
        val fractionalPart = rounded % 10

        stringBuilder.append(integerPart)
        stringBuilder.append('.')
        stringBuilder.append(fractionalPart)
        stringBuilder.append(' ')
        stringBuilder.append(unit)

        val result = stringBuilder.toString()

        // Update cache
        lastRounded = rounded
        lastIsNegative = isNegative
        lastUnit = unit
        cachedResult = result

        return result
    }
}
