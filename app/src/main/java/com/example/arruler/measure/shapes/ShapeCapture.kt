package com.example.arruler.measure.shapes

import com.example.arruler.geometry.PlaneBasis
import com.example.arruler.geometry.Polygon3
import com.example.arruler.geometry.Shape
import com.example.arruler.geometry.Vec3
import com.example.arruler.measure.MeasurePoint
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** The solids the SHAPES mode can measure. */
enum class ShapeKind(val label: String) {
    BOX("Box"),
    CYLINDER("Cylinder"),
    CONE("Cone"),
    SPHERE("Sphere"),
    FRUSTUM("Frustum"),
    PILE("Pile"),
}

/** Whether a named dimension is a length (m) or an area (m2). */
enum class DimKind { LENGTH, AREA }

/** One named dimension of a result, in SI base units (meters or square meters). */
data class Dim(val name: String, val value: Float, val kind: DimKind = DimKind.LENGTH)

/**
 * Outcome of a finished capture.
 *
 * [shape] carries the closed-form solid. [volume] is the number to show; for most kinds it is
 * `shape.volume()`, for PILE it is the midpoint of [volumeLow]..[volumeHigh] (see [ShapeCapture]).
 * [surfaceArea] is null when no closed form exists (PILE). All values are meters / m2 / m3.
 */
data class ShapeResult(
    val shape: Shape,
    val dims: List<Dim>,
    val volume: Float,
    val volumeLow: Float? = null,
    val volumeHigh: Float? = null,
    val surfaceArea: Float? = shape.surfaceArea(),
)

/** A wireframe line segment between two world points (meters). */
typealias Seg = Pair<Vec3, Vec3>

/**
 * Immutable tap-by-tap state machine for one shape [kind]. Feed taps with [add] (world meters,
 * +Y up), take them back with [undo]; every call returns a new state, so it can live in Compose
 * state directly.
 *
 * Taps per kind:
 *  - BOX: 3 base taps (corner, along one edge, across), then 1 top tap. L = |p1-p0|; W = distance of
 *    p2 from the edge line p0-p1 (perpendicular to the first edge, in the base plane); H = distance of
 *    the top tap from the plane through the three base taps.
 *  - CYLINDER / CONE: base centre, a rim point (r = horizontal distance), top / apex tap
 *    (h = vertical distance). The axis is assumed upright (world +Y).
 *  - SPHERE: two taps on opposite sides; r = half the distance. APPROXIMATE: the taps are rarely
 *    exactly diametral, so the true diameter is at least the tapped chord.
 *  - FRUSTUM: bottom centre, bottom rim (r1), top rim (r2 = horizontal distance from the axis through
 *    the bottom centre; h = vertical distance of the top-rim tap above the bottom centre).
 *  - PILE (irregular heap): tap >= 3 points around the base outline, call [closeBase], then tap the
 *    apex. A heap's true volume is unknown, so we give an honest range, not one number:
 *    lower = pyramid over the polygonal base (base area * h / 3; right for a convex, linearly sloping
 *    heap and a lower bound for convex ones that bulge), upper = prism (base area * h; a heap can never
 *    exceed its bounding prism). The shown estimate [ShapeResult.volume] is the midpoint. The real heap
 *    is normally nearer the lower end, so treat the midpoint as rough. h is the apex distance from the
 *    best-fit base plane.
 */
class ShapeCapture private constructor(
    val kind: ShapeKind,
    val taps: List<Vec3>,
    /** PILE only: number of outline taps once the outline is finished (next tap is the apex), else -1. */
    private val outlineSize: Int,
) {
    constructor(kind: ShapeKind) : this(kind, emptyList(), -1)

    /** PILE: the outline is finished, the next tap is the apex. */
    val baseClosed: Boolean get() = outlineSize >= 0

    /** Points tapped so far, as [MeasurePoint]s for the renderer. */
    val points: List<MeasurePoint> get() = taps.map { MeasurePoint(it.x, it.y, it.z) }

    /** Number of taps a fixed-size kind needs; PILE is variable (outline + 1 apex). */
    val requiredTaps: Int? get() = when (kind) {
        ShapeKind.BOX -> 4
        ShapeKind.CYLINDER, ShapeKind.CONE, ShapeKind.FRUSTUM -> 3
        ShapeKind.SPHERE -> 2
        ShapeKind.PILE -> null
    }

    val isComplete: Boolean
        get() = if (kind == ShapeKind.PILE) baseClosed && taps.size > outlineSize else taps.size >= requiredTaps!!

    /** PILE: the outline has >= 3 points and can be closed. */
    val canCloseBase: Boolean get() = kind == ShapeKind.PILE && !baseClosed && taps.size >= 3

    /** Next instruction for the user. */
    val prompt: String
        get() = if (isComplete) "Done. Undo to adjust" else when (kind) {
            ShapeKind.BOX -> listOf(
                "Tap a base corner", "Tap along one base edge", "Tap the opposite side of the base", "Tap the top",
            )[taps.size]
            ShapeKind.CYLINDER -> listOf(
                "Tap the centre of the base", "Tap a point on the rim", "Tap the top",
            )[taps.size]
            ShapeKind.CONE -> listOf(
                "Tap the centre of the base", "Tap a point on the rim", "Tap the apex",
            )[taps.size]
            ShapeKind.SPHERE -> listOf(
                "Tap one side of the sphere", "Tap the opposite side",
            )[taps.size]
            ShapeKind.FRUSTUM -> listOf(
                "Tap the centre of the bottom", "Tap a point on the bottom rim", "Tap a point on the top rim",
            )[taps.size]
            ShapeKind.PILE -> when {
                baseClosed -> "Tap the apex (highest point)"
                taps.size < 3 -> "Tap around the base outline (${taps.size} of 3 minimum)"
                else -> "Tap the next outline point, or Done"
            }
        }

    /** Adds a tap; ignored once complete (or, for PILE, never after the apex). */
    fun add(p: Vec3): ShapeCapture = if (isComplete) this else ShapeCapture(kind, taps + p, outlineSize)

    fun add(p: MeasurePoint): ShapeCapture = add(Vec3(p.x, p.y, p.z))

    /** PILE: finish the outline; no-op otherwise or with fewer than 3 points. */
    fun closeBase(): ShapeCapture = if (canCloseBase) ShapeCapture(kind, taps, taps.size) else this

    /**
     * Removes the last tap. For PILE with a closed outline and no apex yet it only reopens the
     * outline (keeps the points), so Undo steps back through the same states the user went through.
     */
    fun undo(): ShapeCapture = when {
        kind == ShapeKind.PILE && baseClosed && !isComplete -> ShapeCapture(kind, taps, -1)
        taps.isEmpty() -> this
        else -> ShapeCapture(kind, taps.dropLast(1), outlineSize)
    }

    /** Starts over with the same kind. */
    fun reset(): ShapeCapture = ShapeCapture(kind)

    // ---- result -------------------------------------------------------------------------------

    /** The measured shape, or null until [isComplete]. */
    val result: ShapeResult? get() = if (isComplete) computeResult() else null

    private fun computeResult(): ShapeResult = when (kind) {
        ShapeKind.BOX -> {
            val f = boxFrame()
            val s = Shape.Box(f.l, f.w, f.h)
            ShapeResult(s, listOf(Dim("Length", f.l), Dim("Width", f.w), Dim("Height", f.h)), s.volume())
        }
        ShapeKind.CYLINDER -> {
            val r = horiz(taps[0], taps[1]); val h = abs(taps[2].y - taps[0].y)
            val s = Shape.Cylinder(r, h)
            ShapeResult(s, listOf(Dim("Radius", r), Dim("Diameter", 2f * r), Dim("Height", h)), s.volume())
        }
        ShapeKind.CONE -> {
            val r = horiz(taps[0], taps[1]); val h = abs(taps[2].y - taps[0].y)
            val s = Shape.Cone(r, h)
            ShapeResult(s, listOf(Dim("Radius", r), Dim("Diameter", 2f * r), Dim("Height", h)), s.volume())
        }
        ShapeKind.SPHERE -> {
            val r = taps[0].distanceTo(taps[1]) / 2f
            val s = Shape.Sphere(r)
            ShapeResult(s, listOf(Dim("Radius", r), Dim("Diameter", 2f * r)), s.volume())
        }
        ShapeKind.FRUSTUM -> {
            val r1 = horiz(taps[0], taps[1]); val r2 = horiz(taps[0], taps[2])
            val h = abs(taps[2].y - taps[0].y)
            val s = Shape.Frustum(r1, r2, h)
            ShapeResult(
                s, listOf(Dim("Bottom radius", r1), Dim("Top radius", r2), Dim("Height", h)), s.volume(),
            )
        }
        ShapeKind.PILE -> {
            val outline = taps.dropLast(1)
            val apex = taps.last()
            val poly = Polygon3(outline)
            val area = poly.area()
            val h = abs(PlaneBasis.fitFromPoints(outline).signedDistance(apex))
            val pyramid = Shape.Pyramid(area, h)
            val prism = Shape.ExtrudedPolygon(poly, h)
            val lo = pyramid.volume(); val hi = prism.volume()
            ShapeResult(
                shape = pyramid,
                dims = listOf(Dim("Base area", area, DimKind.AREA), Dim("Height", h)),
                volume = (lo + hi) / 2f, volumeLow = lo, volumeHigh = hi, surfaceArea = null,
            )
        }
    }

    private fun horiz(a: Vec3, b: Vec3): Float {
        val dx = b.x - a.x; val dz = b.z - a.z
        return sqrt(dx * dx + dz * dz)
    }

    private class BoxFrame(
        val o: Vec3, val e1: Vec3, val e2: Vec3, val n: Vec3,
        val l: Float, val w: Float, val h: Float, val hSigned: Float,
    )

    /** Box frame from the first 3 taps (+ top tap if present). */
    private fun boxFrame(): BoxFrame {
        val o = taps[0]
        val d1 = taps[1] - o
        val l = d1.length()
        val e1 = d1.normalized()
        val d2 = taps[2] - o
        val perp = d2 - e1 * d2.dot(e1)
        val w = perp.length()
        val e2 = perp.normalized()
        val n = e1.cross(e2).normalized()
        val hs = if (taps.size >= 4) (taps[3] - o).dot(n) else 0f
        return BoxFrame(o, e1, e2, n, l, w, abs(hs), hs)
    }

    // ---- preview ------------------------------------------------------------------------------

    /** Wireframe of what is known so far (world meters). Empty until there is something to draw. */
    val previewSegments: List<Seg>
        get() {
            val t = taps
            val out = ArrayList<Seg>()
            when (kind) {
                ShapeKind.BOX -> {
                    if (t.size == 2) out += t[0] to t[1]
                    if (t.size >= 3) {
                        val f = boxFrame()
                        val a = f.o; val b = f.o + f.e1 * f.l
                        val c = b + f.e2 * f.w; val d = f.o + f.e2 * f.w
                        out += a to b; out += b to c; out += c to d; out += d to a
                        if (t.size >= 4) {
                            val up = f.n * f.hSigned
                            out += (a + up) to (b + up); out += (b + up) to (c + up)
                            out += (c + up) to (d + up); out += (d + up) to (a + up)
                            out += a to (a + up); out += b to (b + up); out += c to (c + up); out += d to (d + up)
                        }
                    }
                }
                ShapeKind.CYLINDER, ShapeKind.CONE -> {
                    if (t.size >= 2) {
                        val r = horiz(t[0], t[1])
                        out += t[0] to t[1]
                        out += circle(t[0], r)
                        if (t.size >= 3) {
                            val top = Vec3(t[0].x, t[2].y, t[0].z)
                            out += t[0] to top
                            if (kind == ShapeKind.CYLINDER) {
                                out += circle(top, r)
                                out += verticals(t[0], r, top.y - t[0].y)
                            } else {
                                for (p in ringPoints(t[0], r, 4)) out += p to top
                            }
                        }
                    }
                }
                ShapeKind.SPHERE -> {
                    if (t.size == 2) {
                        val c = (t[0] + t[1]) * 0.5f
                        val r = t[0].distanceTo(t[1]) / 2f
                        out += t[0] to t[1]
                        out += circle(c, r)
                        out += circle3(c, r, 1); out += circle3(c, r, 2)
                    }
                }
                ShapeKind.FRUSTUM -> {
                    if (t.size >= 2) {
                        val r1 = horiz(t[0], t[1])
                        out += t[0] to t[1]
                        out += circle(t[0], r1)
                        if (t.size >= 3) {
                            val r2 = horiz(t[0], t[2])
                            val topC = Vec3(t[0].x, t[2].y, t[0].z)
                            out += t[0] to topC
                            out += circle(topC, r2)
                            val a = ringPoints(t[0], r1, 4); val b = ringPoints(topC, r2, 4)
                            for (i in 0 until 4) out += a[i] to b[i]
                        }
                    }
                }
                ShapeKind.PILE -> {
                    val outline = if (isComplete) t.dropLast(1) else t
                    for (i in 0 until outline.size - 1) out += outline[i] to outline[i + 1]
                    if (baseClosed) out += outline.last() to outline.first()
                    if (isComplete) for (p in outline) out += p to taps.last()
                }
            }
            return out
        }

    private companion object {
        const val SEGMENTS = 24

        fun ringPoints(c: Vec3, r: Float, n: Int): List<Vec3> = List(n) { i ->
            val a = 2.0 * PI * i / n
            Vec3(c.x + r * cos(a).toFloat(), c.y, c.z + r * sin(a).toFloat())
        }

        /** Horizontal circle (XZ plane) as [SEGMENTS] segments. */
        fun circle(c: Vec3, r: Float): List<Seg> {
            val p = ringPoints(c, r, SEGMENTS)
            return List(SEGMENTS) { i -> p[i] to p[(i + 1) % SEGMENTS] }
        }

        /** Great circle of a sphere in the XY (axis 1) or YZ (axis 2) plane. */
        fun circle3(c: Vec3, r: Float, axis: Int): List<Seg> {
            val p = List(SEGMENTS) { i ->
                val a = 2.0 * PI * i / SEGMENTS
                val u = r * cos(a).toFloat(); val v = r * sin(a).toFloat()
                if (axis == 1) Vec3(c.x + u, c.y + v, c.z) else Vec3(c.x, c.y + u, c.z + v)
            }
            return List(SEGMENTS) { i -> p[i] to p[(i + 1) % SEGMENTS] }
        }

        fun verticals(c: Vec3, r: Float, dy: Float): List<Seg> =
            ringPoints(c, r, 4).map { it to Vec3(it.x, it.y + dy, it.z) }
    }
}

