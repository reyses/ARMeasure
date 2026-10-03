package com.example.arruler.measure.shapes

import com.example.arruler.geometry.Shape
import com.example.arruler.geometry.Tol
import com.example.arruler.geometry.Vec3
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShapeCaptureTest {
    private fun cap(kind: ShapeKind, vararg p: Vec3): ShapeCapture =
        p.fold(ShapeCapture(kind)) { c, v -> c.add(v) }

    private fun dim(r: ShapeResult, name: String) = r.dims.first { it.name == name }.value

    @Test fun boxRotatedBaseExactDims() {
        val a = 0.6f
        val e1 = Vec3(cos(a), 0f, sin(a)); val e2 = Vec3(-sin(a), 0f, cos(a))
        val o = Vec3(1f, 0.2f, -2f)
        // second tap along e1 (L=2), third tap skewed along e1 but 1.5 across; top 0.8 up
        val c = cap(
            ShapeKind.BOX, o, o + e1 * 2f, o + e1 * 0.7f + e2 * 1.5f, o + e1 * 0.3f + e2 * 0.1f + Vec3(0f, 0.8f, 0f),
        )
        val r = c.result!!
        Tol.near(2f, dim(r, "Length")); Tol.near(1.5f, dim(r, "Width")); Tol.near(0.8f, dim(r, "Height"))
        Tol.near(2.4f, r.volume)
        assertEquals(12, c.previewSegments.size)
    }

    @Test fun cylinder() {
        val r = cap(ShapeKind.CYLINDER, Vec3(0f, 0f, 0f), Vec3(0.3f, 0f, 0.4f), Vec3(0.2f, 1f, 0.1f)).result!!
        Tol.near(0.7854f, r.volume, 1e-3f)
        assertTrue(r.shape is Shape.Cylinder)
        Tol.near(0.5f, dim(r, "Radius"))
        assertNotNull(r.surfaceArea)
    }

    @Test fun cone() {
        val r = cap(ShapeKind.CONE, Vec3(1f, 1f, 1f), Vec3(1.5f, 1f, 1f), Vec3(1f, 2f, 1f)).result!!
        Tol.near((PI / 12.0).toFloat(), r.volume, 1e-4f)
        assertTrue(r.shape is Shape.Cone)
    }

    @Test fun frustumMatchesFormula() {
        val r = cap(ShapeKind.FRUSTUM, Vec3(0f, 0f, 0f), Vec3(1f, 0f, 0f), Vec3(0f, 2f, 0.5f)).result!!
        Tol.near(Shape.Frustum(1f, 0.5f, 2f).volume(), r.volume)
        Tol.near(0.5f, dim(r, "Top radius")); Tol.near(2f, dim(r, "Height"))
    }

    @Test fun sphere() {
        val r = cap(ShapeKind.SPHERE, Vec3(0f, 0f, 0f), Vec3(0f, 1f, 0f)).result!!
        Tol.near(Shape.Sphere(0.5f).volume(), r.volume)
        Tol.near(0.5236f, r.volume, 1e-3f)
    }

    @Test fun pileRangeSquareBase() {
        val c = cap(
            ShapeKind.PILE, Vec3(0f, 0f, 0f), Vec3(2f, 0f, 0f), Vec3(2f, 0f, 2f), Vec3(0f, 0f, 2f),
        ).closeBase().add(Vec3(1f, 1f, 1f))
        assertTrue(c.isComplete)
        val r = c.result!!
        Tol.near(4f / 3f, r.volumeLow!!); Tol.near(4f, r.volumeHigh!!)
        Tol.near(8f / 3f, r.volume)
        assertNull(r.surfaceArea)
        Tol.near(4f, dim(r, "Base area")); Tol.near(1f, dim(r, "Height"))
    }

    @Test fun pileNeedsThreeAndClose() {
        var c = cap(ShapeKind.PILE, Vec3(0f, 0f, 0f), Vec3(1f, 0f, 0f))
        assertFalse(c.canCloseBase)
        assertEquals(c, c.closeBase())
        c = c.add(Vec3(0f, 0f, 1f))
        assertTrue(c.canCloseBase)
        // 4-point outline closed, then apex: must not read as complete before the apex
        val c4 = c.add(Vec3(1f, 0f, 1f)).closeBase()
        assertFalse(c4.isComplete)
        assertTrue(c4.add(Vec3(0.5f, 1f, 0.5f)).isComplete)
    }

    @Test fun pileUndoSteps() {
        val c = cap(ShapeKind.PILE, Vec3(0f, 0f, 0f), Vec3(1f, 0f, 0f), Vec3(0f, 0f, 1f))
            .closeBase().add(Vec3(0.3f, 1f, 0.3f))
        assertTrue(c.isComplete)
        val u1 = c.undo()
        assertTrue(u1.baseClosed); assertFalse(u1.isComplete); assertEquals(3, u1.taps.size)
        val u2 = u1.undo()
        assertFalse(u2.baseClosed); assertEquals(3, u2.taps.size)
        assertEquals(2, u2.undo().taps.size)
    }

    @Test fun undoTransitionsFixedKinds() {
        var c = cap(ShapeKind.CYLINDER, Vec3(0f, 0f, 0f), Vec3(1f, 0f, 0f), Vec3(0f, 1f, 0f))
        assertTrue(c.isComplete); assertNotNull(c.result)
        c = c.undo()
        assertFalse(c.isComplete); assertNull(c.result); assertEquals(2, c.taps.size)
        assertEquals("Tap the top", c.prompt)
        assertEquals(0, c.undo().undo().undo().taps.size)
        // taps after completion are ignored
        val done = cap(ShapeKind.SPHERE, Vec3(0f, 0f, 0f), Vec3(1f, 0f, 0f))
        assertEquals(done, done.add(Vec3(5f, 5f, 5f)))
    }

    @Test fun promptSequences() {
        fun prompts(kind: ShapeKind): List<String> {
            val out = ArrayList<String>(); var c = ShapeCapture(kind)
            repeat(c.requiredTaps!!) { i -> out += c.prompt; c = c.add(Vec3(i.toFloat(), i.toFloat(), 0f)) }
            out += c.prompt
            return out
        }
        assertEquals(
            listOf("Tap a base corner", "Tap along one base edge", "Tap the opposite side of the base", "Tap the top", "Done. Undo to adjust"),
            prompts(ShapeKind.BOX),
        )
        assertEquals(
            listOf("Tap the centre of the base", "Tap a point on the rim", "Tap the top", "Done. Undo to adjust"),
            prompts(ShapeKind.CYLINDER),
        )
        assertEquals("Tap the apex", prompts(ShapeKind.CONE)[2])
        assertEquals(listOf("Tap one side of the sphere", "Tap the opposite side", "Done. Undo to adjust"), prompts(ShapeKind.SPHERE))
        assertEquals("Tap a point on the top rim", prompts(ShapeKind.FRUSTUM)[2])
        var p = ShapeCapture(ShapeKind.PILE)
        assertTrue(p.prompt.startsWith("Tap around the base outline"))
        p = p.add(Vec3(0f, 0f, 0f)).add(Vec3(1f, 0f, 0f)).add(Vec3(0f, 0f, 1f))
        assertEquals("Tap the next outline point, or Done", p.prompt)
        assertEquals("Tap the apex (highest point)", p.closeBase().prompt)
    }

    @Test fun previewCounts() {
        val cyl = cap(ShapeKind.CYLINDER, Vec3(0f, 0f, 0f), Vec3(1f, 0f, 0f), Vec3(0f, 1f, 0f))
        assertEquals(1 + 24 + 1 + 24 + 4, cyl.previewSegments.size)
        val fr = cap(ShapeKind.FRUSTUM, Vec3(0f, 0f, 0f), Vec3(1f, 0f, 0f), Vec3(0.5f, 1f, 0f))
        assertEquals(1 + 24 + 1 + 24 + 4, fr.previewSegments.size)
        assertEquals(12, ShapePreview.segments(
            cap(ShapeKind.BOX, Vec3(0f, 0f, 0f), Vec3(1f, 0f, 0f), Vec3(0f, 0f, 1f), Vec3(0f, 1f, 0f))).size)
    }
}
