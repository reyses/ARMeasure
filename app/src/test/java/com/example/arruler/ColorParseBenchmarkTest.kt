package com.example.arruler

import org.junit.Test
import kotlin.system.measureNanoTime

class ColorParseBenchmarkTest {

    @Test
    fun benchmarkColorParsing() {
        val iterations = 1_000_000
        val colorString = "#34C759"

        // Warmup
        repeat(100_000) {
            parseColorSimulated(colorString)
            accessConstant()
        }

        val baselineTime = measureNanoTime {
            repeat(iterations) {
                parseColorSimulated(colorString)
            }
        }

        val optimizedTime = measureNanoTime {
            repeat(iterations) {
                accessConstant()
            }
        }

        println("Baseline (Parse) Time: ${baselineTime / 1_000_000} ms")
        println("Optimized (Constant) Time: ${optimizedTime / 1_000_000} ms")

        if (baselineTime > 0) {
             println("Improvement: ${(baselineTime - optimizedTime).toDouble() / baselineTime * 100}%")
             println("Speedup: ${baselineTime.toDouble() / optimizedTime}x")
        }
    }

    // Simulates Android's Color.parseColor for hex strings
    private fun parseColorSimulated(colorString: String): Int {
        if (colorString[0] == '#') {
            // Use a long to avoid rollovers on #ffXXXXXX
            var color = java.lang.Long.parseLong(colorString.substring(1), 16)
            if (colorString.length == 7) {
                // Set the alpha value
                color = color or 0x00000000ff000000L
            } else if (colorString.length != 9) {
                throw IllegalArgumentException("Unknown color")
            }
            return color.toInt()
        }
        throw IllegalArgumentException("Unknown color")
    }

    private val CONSTANT_COLOR = 0xFF34C759.toInt()

    private fun accessConstant(): Int {
        return CONSTANT_COLOR
    }
}
