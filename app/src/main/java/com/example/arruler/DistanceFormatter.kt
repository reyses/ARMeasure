package com.example.arruler

import kotlin.math.roundToInt
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
        val rounded = (absValue * 10).roundToInt()
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
