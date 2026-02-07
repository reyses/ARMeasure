package com.example.arruler

import kotlin.math.abs

class DistanceFormatter {
    private val stringBuilder = StringBuilder()

    fun format(value: Float, unit: String): String {
        stringBuilder.setLength(0)

        // Manual formatting to 1 decimal place to avoid String.format allocation
        if (value < 0) {
            stringBuilder.append('-')
        }

        val absValue = abs(value)
        // Rounding Mode: HALF_UP
        // Adding 0.5 and casting to long achieves HALF_UP for positive numbers
        val rounded = (absValue * 10 + 0.5f).toLong()
        val integerPart = rounded / 10
        val fractionalPart = rounded % 10

        stringBuilder.append(integerPart)
        stringBuilder.append('.')
        stringBuilder.append(fractionalPart)
        stringBuilder.append(' ')
        stringBuilder.append(unit)

        return stringBuilder.toString()
    }
}
