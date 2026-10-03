package com.example.arruler.geometry

import kotlin.math.PI
import kotlin.math.sqrt

/** Closed-form volumes (m^3 for meter inputs). */
object Volumes {
    private val PI_F = PI.toFloat()

    fun prism(baseArea: Float, height: Float): Float = baseArea * height
    fun box(l: Float, w: Float, h: Float): Float = l * w * h
    fun cylinder(r: Float, h: Float): Float = PI_F * r * r * h
    fun cone(r: Float, h: Float): Float = PI_F * r * r * h / 3f
    fun sphere(r: Float): Float = 4f / 3f * PI_F * r * r * r
    fun frustum(r1: Float, r2: Float, h: Float): Float = PI_F * h / 3f * (r1 * r1 + r1 * r2 + r2 * r2)
    fun pyramid(baseArea: Float, h: Float): Float = baseArea * h / 3f
    fun extrudedPolygon(poly: Polygon3, height: Float): Float = prism(poly.area(), height)
}

/** Solid shapes with closed-form volume and (where it exists) surface area. */
sealed class Shape {
    abstract fun volume(): Float

    /** Total surface area, or null when no closed form exists from the parameters. */
    abstract fun surfaceArea(): Float?

    data class Box(val l: Float, val w: Float, val h: Float) : Shape() {
        override fun volume() = Volumes.box(l, w, h)
        override fun surfaceArea(): Float = 2f * (l * w + l * h + w * h)
    }

    data class Cylinder(val r: Float, val h: Float) : Shape() {
        override fun volume() = Volumes.cylinder(r, h)
        override fun surfaceArea(): Float = 2f * PI.toFloat() * r * (r + h)
    }

    data class Cone(val r: Float, val h: Float) : Shape() {
        override fun volume() = Volumes.cone(r, h)
        override fun surfaceArea(): Float = PI.toFloat() * r * (r + sqrt(r * r + h * h))
    }

    data class Sphere(val r: Float) : Shape() {
        override fun volume() = Volumes.sphere(r)
        override fun surfaceArea(): Float = 4f * PI.toFloat() * r * r
    }

    data class Frustum(val r1: Float, val r2: Float, val h: Float) : Shape() {
        override fun volume() = Volumes.frustum(r1, r2, h)
        override fun surfaceArea(): Float {
            val slant = sqrt(h * h + (r1 - r2) * (r1 - r2))
            return PI.toFloat() * (r1 * r1 + r2 * r2 + (r1 + r2) * slant)
        }
    }

    /** Surface area is not determined by base area and height alone, so it is null. */
    data class Pyramid(val baseArea: Float, val h: Float) : Shape() {
        override fun volume() = Volumes.pyramid(baseArea, h)
        override fun surfaceArea(): Float? = null
    }

    /** Right prism over a planar polygon: 2 * base + perimeter * height. */
    data class ExtrudedPolygon(val poly: Polygon3, val height: Float) : Shape() {
        override fun volume() = Volumes.extrudedPolygon(poly, height)
        override fun surfaceArea(): Float = 2f * poly.area() + poly.perimeter() * height
    }
}
