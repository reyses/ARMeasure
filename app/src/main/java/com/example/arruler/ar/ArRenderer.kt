package com.example.arruler.ar

import android.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.example.arruler.measure.MeasurePoint
import com.google.android.filament.MaterialInstance
import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.Quaternion
import io.github.sceneview.ar.ARSceneScope
import kotlin.math.sqrt

/**
 * Draws points, segments and polygon outlines at world positions (meters).
 * The only class that knows about SceneView nodes.
 *
 * SceneView 4 builds nodes declaratively, so [render] / [renderExtra] only publish what should be
 * on screen as Compose state and [Nodes] (called from the `ARSceneView` content block) emits the
 * matching node composables, which SceneView creates, updates and destroys with the composition.
 */
class ArRenderer {

    /** One cylinder: centre, orientation (+Y onto the segment) and the unit-cylinder scale. */
    internal class Segment(val center: Float3, val rotation: Quaternion, val length: Float, val radius: Float)

    private var points by mutableStateOf<List<MeasurePoint>>(emptyList())
    private var segments by mutableStateOf<List<Segment>>(emptyList())
    private var segmentsAreFinal by mutableStateOf(false)
    private var extras by mutableStateOf<List<Segment>>(emptyList())

    /**
     * Shows exactly [points] as spheres joined by segments; [closed] adds the last-to-first
     * edge (polygon outline). [final] picks the thick red style instead of the thin live one.
     */
    fun render(points: List<MeasurePoint>, closed: Boolean = false, final: Boolean = false) {
        this.points = points.toList()
        val count = when {
            points.size < 2 -> 0
            closed && points.size >= 3 -> points.size
            else -> points.size - 1
        }
        val radius = if (final) FINAL_RADIUS else LIVE_RADIUS
        segments = List(count) { i -> segment(points[i], points[(i + 1) % points.size], radius) }
        segmentsAreFinal = final
    }

    /** Extra thin segments (e.g. the height line), independent of [render]. Empty list removes them. */
    fun renderExtra(segments: List<Pair<MeasurePoint, MeasurePoint>>) {
        extras = segments.map { (a, b) -> segment(a, b, LIVE_RADIUS) }
    }

    fun clear() {
        render(emptyList())
        renderExtra(emptyList())
    }

    fun release() {
        clear()
    }

    /** Emits the nodes for the current state; call it from the `ARSceneView` content block. */
    @Composable
    internal fun ARSceneScope.Nodes() {
        // Declared before the nodes so they are disposed after them (a material instance must
        // outlive the renderables that use it).
        val pointMaterial = remember(materialLoader) {
            materialLoader.createColorInstance(Color.RED, 0f, 0.4f, 0.5f)
        }
        val liveMaterial = remember(materialLoader) {
            materialLoader.createColorInstance(Color.YELLOW, 0f, 0.4f, 0.5f)
        }
        val finalMaterial = remember(materialLoader) {
            materialLoader.createColorInstance(Color.RED, 0f, 0.4f, 0.5f)
        }
        DisposableEffect(materialLoader) {
            onDispose {
                materialLoader.destroyMaterialInstance(pointMaterial)
                materialLoader.destroyMaterialInstance(liveMaterial)
                materialLoader.destroyMaterialInstance(finalMaterial)
            }
        }

        points.forEachIndexed { i, p ->
            key("p$i") {
                SphereNode(
                    radius = POINT_RADIUS,
                    materialInstance = pointMaterial,
                    position = Float3(p.x, p.y, p.z),
                )
            }
        }
        val segmentMaterial = if (segmentsAreFinal) finalMaterial else liveMaterial
        segments.forEachIndexed { i, s ->
            key("s$i") { SegmentNode(s, segmentMaterial) }
        }
        extras.forEachIndexed { i, s ->
            key("e$i") { SegmentNode(s, liveMaterial) }
        }
    }

    @Composable
    private fun ARSceneScope.SegmentNode(s: Segment, material: MaterialInstance) {
        // Unit-height, unit-radius cylinder centred on its node; scaled per segment.
        CylinderNode(
            radius = 1f,
            height = 1f,
            materialInstance = material,
            position = s.center,
            rotation = s.rotation.toEulerAngles(),
            scale = Float3(s.radius, s.length, s.radius),
        )
    }

    internal companion object {
        const val POINT_RADIUS = 0.015f
        const val LIVE_RADIUS = 0.003f
        const val FINAL_RADIUS = 0.005f

        fun segment(a: MeasurePoint, b: MeasurePoint, radius: Float): Segment {
            val length = a.distanceTo(b)
            val rotation = if (length > 1e-6f) {
                rotationFromUpTo((b.x - a.x) / length, (b.y - a.y) / length, (b.z - a.z) / length)
            } else {
                Quaternion(0f, 0f, 0f, 1f)
            }
            return Segment(
                center = Float3((a.x + b.x) / 2f, (a.y + b.y) / 2f, (a.z + b.z) / 2f),
                rotation = rotation,
                length = length,
                radius = radius,
            )
        }

        /** Shortest rotation taking +Y onto the unit vector (dx, dy, dz). */
        fun rotationFromUpTo(dx: Float, dy: Float, dz: Float): Quaternion {
            if (dy < -0.99999f) return Quaternion(1f, 0f, 0f, 0f)
            // axis = up x dir = (dz, 0, -dx); w = 1 + up.dir
            val w = 1f + dy
            val len = sqrt(dz * dz + dx * dx + w * w)
            return Quaternion(dz / len, 0f, -dx / len, w / len)
        }
    }
}
